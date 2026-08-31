package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorSynthesisTest {
    private final SupervisorSynthesizer synthesizer = new SupervisorSynthesizer();

    @Test
    void usesModelBackedSupervisorToDecideCrossDomainCorrelation() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY","DEVICE","SECURITY"],
                 "evidenceRefs":["energy:MTR-2","device:D-2","security:SEC-1"],"confidence":0.7,
                 "conclusion":"三项观测在同一时段相互印证，存在关联。","uncertainties":[]}
                """))));

        var result = synthesizer.synthesize(model, crossDomainPlan(), List.of(
                supported(),
                new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                        "device D-2 is offline", List.of("device:D-2"), .7, List.of()),
                new ExpertFinding(ExpertDomain.SECURITY, FindingStatus.SUPPORTED,
                        "security event SEC-1 is active", List.of("security:SEC-1"), .7, List.of())));

        assertThat(result.conclusion()).isEqualTo("三项观测在同一时段相互印证，存在关联。");
    }

    @Test
    void synthesizesAllValidatedSupportedFindingsWithoutAProviderEcho() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device D-2 is offline", List.of("device:D-2"), .7, List.of());

        var result = synthesizer.synthesize(plan(), List.of(device, supported()));

        assertThat(result.status()).isEqualTo(FindingStatus.SUPPORTED);
        assertThat(result.conclusion())
                .isEqualTo("MTR-2 consumption is 18.5 above baseline；device D-2 is offline");
        assertThat(result.evidenceRefs()).containsExactlyInAnyOrder("energy:MTR-2", "device:D-2");
        assertThat(result.confidence()).isEqualTo(.7);
    }

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
    void derivesUncertaintyWhenAnExpertFailed() {
        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY"],
                 "evidenceRefs":["energy:MTR-2"],"confidence":0.6,"uncertainties":[]}
                """, plan(), List.of(supported(), failedDevice()));

        assertThat(result.uncertainties()).containsExactly("DEVICE: failed to query device");
    }

    @Test
    void capsSupportedConfidenceAtTheWeakestValidatedFinding() {
        ExpertFinding weak = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device D-2 is offline", List.of("device:D-2"), .1, List.of());

        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","selectedDomains":["ENERGY","DEVICE"],
                 "evidenceRefs":["energy:MTR-2","device:D-2"],"confidence":1.0,"uncertainties":[]}
                """, plan(), List.of(supported(), weak));

        assertThat(result.confidence()).isEqualTo(.1);
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

    @Test
    void discardsModelSelectionsWhenSynthesisIsNotSupported() {
        var insufficient = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE,
                "insufficient evidence", List.of(), 0, List.of("query meter"));

        var result = synthesizer.parseAndValidate("""
                {"status":"INSUFFICIENT_EVIDENCE","selectedDomains":["ENERGY"],
                 "evidenceRefs":["energy:MTR-2"],"confidence":0.8,"uncertainties":["missing data"]}
                """, plan(), List.of(insufficient));

        assertThat(result.status()).isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.evidenceRefs()).isEmpty();
        assertThat(result.confidence()).isZero();
    }

    @Test
    void discardsOutOfPlanModelSelectionsWhenSynthesisIsNotSupported() {
        var insufficient = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE,
                "insufficient evidence", List.of(), 0, List.of("query meter"));
        var energyOnlyPlan = new SupervisorPlan("energy question", Set.of(ExpertDomain.ENERGY),
                Map.of(ExpertDomain.ENERGY, "energy"), "energy-only");

        var result = synthesizer.parseAndValidate("""
                {"status":"INSUFFICIENT_EVIDENCE","selectedDomains":["SECURITY"],
                 "evidenceRefs":["stale:ref"],"confidence":0.8,"uncertainties":["missing data"]}
                """, energyOnlyPlan, List.of(insufficient));

        assertThat(result.status()).isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.evidenceRefs()).isEmpty();
        assertThat(result.confidence()).isZero();
    }

    @Test
    void derivesUncertaintiesWhenModelOmitsRequiredDisclosure() {
        var insufficient = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE,
                "insufficient evidence", List.of(), 0, List.of("query meter"));

        var result = synthesizer.parseAndValidate("""
                {"status":"INSUFFICIENT_EVIDENCE","selectedDomains":[],
                 "evidenceRefs":[],"confidence":0,"uncertainties":[]}
                """, plan(), List.of(insufficient));

        assertThat(result.uncertainties()).containsExactly("ENERGY: insufficient evidence");
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

    private static SupervisorPlan crossDomainPlan() {
        return new SupervisorPlan("energy question", Set.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE, ExpertDomain.SECURITY),
                Map.of(ExpertDomain.ENERGY, "energy", ExpertDomain.DEVICE, "device", ExpertDomain.SECURITY, "security"), "cross-domain");
    }
}
