package com.example.smartpark.collaboration.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertFindingTest {
    @Test void supportsAllDomainsAndStatuses() {
        for (ExpertDomain domain : ExpertDomain.values()) {
            assertThat(new ExpertFinding(domain, FindingStatus.SUPPORTED, "finding", List.of("tool:1"), .8, List.of()).domain()).isEqualTo(domain);
        }
        assertThat(new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.FAILED, "failed to analyze", List.of(), 0, List.of()).status())
                .isEqualTo(FindingStatus.FAILED);
    }

    @Test void rejectsInvalidConfidenceAndUnsupportedConfidence() {
        assertThatThrownBy(() -> new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED, "x", List.of(), 1.1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.INSUFFICIENT_EVIDENCE, "x", List.of(), .1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
