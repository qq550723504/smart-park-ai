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

    @Test void acceptsConclusionBoundToSupportedFindings() {
        var result = synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","conclusion":"MTR-2 consumption is 18.5 above baseline","evidenceRefs":["energy:MTR-2"],"confidence":0.8,"uncertainties":[]}
                """, plan(), List.of(supported()));
        assertThat(result.evidenceRefs()).containsExactly("energy:MTR-2");
    }

    @Test void requiresUncertaintyWhenAnExpertFailed() {
        var failed = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.FAILED, "failed to query device", List.of(), 0, List.of("retry"));
        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","conclusion":"MTR-2 consumption is 18.5 above baseline","evidenceRefs":["energy:MTR-2"],"confidence":0.6,"uncertainties":[]}
                """, plan(), List.of(supported(), failed))).hasMessageContaining("disclosed");
    }

    @Test void rejectsNewFactsAndEvidence() {
        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","conclusion":"MTR-9 consumption is 99 above baseline","evidenceRefs":["invented:9"],"confidence":0.9,"uncertainties":[]}
                """, plan(), List.of(supported()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cannotClaimSupportWhenEveryFindingLacksEvidence() {
        var insufficient = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE,
                "insufficient evidence", List.of(), 0, List.of("query meter"));
        assertThatThrownBy(() -> synthesizer.parseAndValidate("""
                {"status":"SUPPORTED","conclusion":"root cause confirmed","evidenceRefs":[],"confidence":0.5,"uncertainties":["missing data"]}
                """, plan(), List.of(insufficient))).hasMessageContaining("without a supported finding");
    }

    private static ExpertFinding supported() {
        return new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "MTR-2 consumption is 18.5 above baseline", List.of("energy:MTR-2"), .8, List.of());
    }

    private static SupervisorPlan plan() {
        return new SupervisorPlan("energy question", Set.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE),
                Map.of(ExpertDomain.ENERGY, "energy", ExpertDomain.DEVICE, "device"), "cross-domain");
    }
}
