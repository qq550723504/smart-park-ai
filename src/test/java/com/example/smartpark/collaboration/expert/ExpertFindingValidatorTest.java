package com.example.smartpark.collaboration.expert;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertFindingValidatorTest {
    private final ExpertFindingValidator validator = new ExpertFindingValidator();

    @Test void downgradesSupportedFindingWhenOnlyAReferenceButNoResultWasObserved() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "consumption is above baseline", java.util.List.of("tool:energy:1"), .8, java.util.List.of());
        assertThat(validator.validate(finding, Set.of("tool:energy:1")).status())
                .isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test void downgradesAFabricatedReferenceWithoutInventingEvidence() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.SECURITY, FindingStatus.SUPPORTED,
                "access anomaly", java.util.List.of("raw-camera:9"), .9, java.util.List.of());
        ExpertFinding validated = validator.validate(finding, Set.of("tool:security:1"));
        assertThat(validated.status()).isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
        assertThat(validated.evidenceRefs()).isEmpty();
        assertThat(validated.confidence()).isZero();
    }

    @Test void acceptsFailureWithoutEvidence() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.FAILED,
                "failed to query device", java.util.List.of(), 0, java.util.List.of("retry"));
        assertThat(validator.validate(finding, Set.of())).isEqualTo(finding);
    }

    @Test void downgradesFindingThatContradictsTheStructuredToolResult() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device is offline", java.util.List.of("tool:device:1"), .9, java.util.List.of());
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:device:1", "{\"deviceId\":\"D-1\",\"status\":\"ONLINE\"}");

        ExpertFinding validated = validator.validateWithObservations(finding, ledger.snapshotObservations());

        assertThat(validated.status()).isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
        assertThat(validated.evidenceRefs()).isEmpty();
        assertThat(validated.confidence()).isZero();
    }

    @Test void replacesModelQuantitativeClaimsWithTheCitedStructuredResult() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "consumption is 9999 kWh and baseline is 1 kWh",
                java.util.List.of("tool:energy:1"), .9, java.util.List.of());
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:energy:1", "{\"consumptionKwh\":120,\"baselineKwh\":100}");

        ExpertFinding validated = validator.validateWithObservations(finding, ledger.snapshotObservations());

        assertThat(validated.status()).isEqualTo(FindingStatus.SUPPORTED);
        assertThat(validated.conclusion())
                .contains("tool:energy:1", "\"consumptionKwh\":120", "\"baselineKwh\":100")
                .doesNotContain("9999");
    }

    @Test void downgradesSuccessfulTransportThatReturnedAnErrorObservation() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED,
                "device is offline", java.util.List.of("tool:device:1"), .9, java.util.List.of());
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:device:1", "{\"error\":\"unknown device\"}");

        assertThat(validator.validateWithObservations(finding, ledger.snapshotObservations()).status())
                .isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test void acceptsAnExplicitlyEmptyErrorsCollection() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "consumption is available", java.util.List.of("tool:energy:1"), .9, java.util.List.of());
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:energy:1", "{\"success\":true,\"errors\":[],\"consumptionKwh\":120}");

        assertThat(validator.validateWithObservations(finding, ledger.snapshotObservations()).status())
                .isEqualTo(FindingStatus.SUPPORTED);
    }
}
