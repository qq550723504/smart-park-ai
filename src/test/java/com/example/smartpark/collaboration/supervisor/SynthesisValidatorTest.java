package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.Synthesis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynthesisValidatorTest {
    private final SynthesisValidator validator = new SynthesisValidator();
    private final ExpertFinding energy = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
            "MTR-2 reports 18.5 kWh", List.of("energy:MTR-2"), .8, List.of());

    @Test
    void acceptsEvidenceFromSelectedSupportedFinding() {
        Synthesis synthesis = new Synthesis(FindingStatus.SUPPORTED, "MTR-2 reports 18.5 kWh",
                List.of("energy:MTR-2"), .7, List.of());

        assertThat(validator.validate(synthesis, List.of(energy), Set.of(ExpertDomain.ENERGY)))
                .isEqualTo(synthesis);
    }

    @Test
    void rejectsEvidenceOwnedByUnselectedSupportedFinding() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "D-2 offline", List.of("device:D-2"), .7, List.of());
        Synthesis synthesis = new Synthesis(FindingStatus.SUPPORTED, "MTR-2 reports 18.5 kWh",
                List.of("energy:MTR-2", "device:D-2"), .7, List.of());

        assertThatThrownBy(() -> validator.validate(
                synthesis, List.of(energy, device), Set.of(ExpertDomain.ENERGY)))
                .hasMessageContaining("selected findings");
    }

    @Test
    void rejectsSelectedDomainWithoutSupportedFinding() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.FAILED,
                "query failed", List.of(), 0, List.of("retry"));
        Synthesis synthesis = new Synthesis(FindingStatus.SUPPORTED, "query failed",
                List.of("energy:MTR-2"), .5, List.of("device failed"));

        assertThatThrownBy(() -> validator.validate(
                synthesis, List.of(energy, device), Set.of(ExpertDomain.DEVICE)))
                .hasMessageContaining("SUPPORTED");
    }

    @Test
    void allowsPartialFailureOnlyWhenUncertaintyIsExplicit() {
        ExpertFinding device = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.INSUFFICIENT_EVIDENCE,
                "device state is uncertain", List.of(), 0, List.of("query device"));
        Synthesis synthesis = new Synthesis(FindingStatus.SUPPORTED, "MTR-2 reports 18.5 kWh",
                List.of("energy:MTR-2"), .5, List.of("device finding is uncertain"));

        assertThat(validator.validate(
                synthesis, List.of(energy, device), Set.of(ExpertDomain.ENERGY))).isEqualTo(synthesis);
    }
}
