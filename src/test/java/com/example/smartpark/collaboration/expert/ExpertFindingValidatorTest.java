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

    @Test void preservesSupportedFindingWhenEveryReferenceWasObserved() {
        ExpertFinding finding = new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED,
                "consumption is above baseline", java.util.List.of("tool:energy:1"), .8, java.util.List.of());
        assertThat(validator.validate(finding, Set.of("tool:energy:1"))).isEqualTo(finding);
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
}
