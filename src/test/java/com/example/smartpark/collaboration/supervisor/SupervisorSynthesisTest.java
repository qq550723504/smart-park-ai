package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorSynthesisTest {
    private final SupervisorSynthesizer synthesizer = new SupervisorSynthesizer();

    @Test
    void buildsConclusionVerbatimFromSelectedSupportedFinding() {
        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],"evidenceRefs":["energy:MTR-2"],"confidence":0.8,"uncertainties":[]}
                """, plan(), List.of(supported()));

        assertThat(result.conclusion()).isEqualTo("MTR-2 consumption is 18.5 above baseline");
        assertThat(result.evidenceRefs()).containsExactly("energy:MTR-2");
    }

    @Test
    void ignoresFreeTextConclusionAndCannotRewriteQualitativeClaim() {
        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],
                 "conclusion":"MTR-2 is normal and below baseline",
                 "evidenceRefs":["energy:MTR-2"],"confidence":0.8,"uncertainties":[]}
                """, plan(), List.of(supported()));

        assertThat(result.conclusion()).isEqualTo("MTR-2 consumption is 18.5 above baseline");
        assertThat(result.conclusion()).doesNotContain("normal", "below");
    }

    @Test
    void combinesSelectedFindingsInStableDomainOrder() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device D-2 is offline", List.of("device:D-2"), .7, List.of());

        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["DEVICE","ENERGY"],
                 "evidenceRefs":["device:D-2","energy:MTR-2"],"confidence":0.7,"uncertainties":[]}
                """, plan(), List.of(device, supported()));

        assertThat(result.conclusion())
                .isEqualTo("MTR-2 consumption is 18.5 above baseline；device D-2 is offline");
        assertThat(result.evidenceRefs()).containsExactlyInAnyOrder("energy:MTR-2", "device:D-2");
    }

    @Test
    void rejectsOmissionOfAnySupportedFinding() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device D-2 is offline", List.of("device:D-2"), .7, List.of());

        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],
                 "evidenceRefs":["energy:MTR-2"],"confidence":0.7,"uncertainties":[]}
                """, plan(), List.of(supported(), device)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all SUPPORTED");
    }

    @Test
    void requiresUncertaintyWhenAnExpertFailed() {
        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],
                 "evidenceRefs":["energy:MTR-2"],"confidence":0.6,"uncertainties":[]}
                """, plan(), List.of(supported(), failedDevice()))).hasMessageContaining("disclosed");
    }

    @Test
    void rejectsEvidenceFromAnUnselectedFinding() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device D-2 is offline", List.of("device:D-2"), .7, List.of());

        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],
                 "evidenceRefs":["energy:MTR-2","device:D-2"],"confidence":0.7,"uncertainties":[]}
                """, plan(), List.of(supported(), device)))
                .hasMessageContaining("selected findings");
    }

    @Test
    void rejectsSelectingFailedDomain() {
        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["DEVICE"],
                 "evidenceRefs":[],"confidence":0.5,"uncertainties":["device failed"]}
                """, plan(), List.of(supported(), failedDevice())))
                .hasMessageContaining("SUPPORTED");
    }

    @Test
    void returnsDeterministicInsufficientConclusionWhenNothingIsSupported() {
        var insufficient = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE,
                "insufficient evidence", List.of(), 0, List.of("query meter"));

        var result = synthesizer.parseAndValidate("""
                {"status":"INSUFFICIENT_EVIDENCE","selectedDomains":[],
                 "evidenceRefs":[],"confidence":0,"uncertainties":["missing data"]}
                """, plan(), List.of(insufficient));

        assertThat(result.conclusion()).isEqualTo("没有可验证的专家结论");
    }

    private static ExpertFinding supported() {
        return new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "MTR-2 consumption is 18.5 above baseline", List.of("energy:MTR-2"), .8, List.of());
    }

    private static ExpertFinding failedDevice() {
        return new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.FAILED,
                "failed to query device", List.of(), 0, List.of("retry"));
    }

    private static SupervisorPlan plan() {
        return new SupervisorPlan("energy question", Set.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE),
                Map.of(ExpertDomain.ENERGY, "energy", ExpertDomain.DEVICE, "device"), "cross-domain");
    }
}
