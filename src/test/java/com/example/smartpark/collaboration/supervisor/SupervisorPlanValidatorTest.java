package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorPlanValidatorTest {

    private final SupervisorPlanValidator validator = new SupervisorPlanValidator();

    @Test
    void matchesEnglishRoutingKeywordsOnTokenBoundaries() {
        // "outdoor" contains "door" as a substring but is an unrelated token;
        // raw substring matching would wrongly require the SECURITY expert.
        assertThat(validator.expectedDomains("outdoor HVAC unit is offline"))
                .isEqualTo(Set.of(ExpertDomain.DEVICE));
    }

    @Test
    void stillMatchesStandaloneEnglishAndChineseTerms() {
        assertThat(validator.expectedDomains("check the door access logs"))
                .isEqualTo(Set.of(ExpertDomain.SECURITY));
        assertThat(validator.expectedDomains("能耗和告警情况"))
                .isEqualTo(Set.of(ExpertDomain.ENERGY, ExpertDomain.SECURITY));
        assertThat(validator.expectedDomains("device offline alarm"))
                .isEqualTo(Set.of(ExpertDomain.DEVICE, ExpertDomain.SECURITY));
    }
}
