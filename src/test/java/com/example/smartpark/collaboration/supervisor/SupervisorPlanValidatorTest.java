package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .isEqualTo(Set.of(ExpertDomain.ENERGY));
        assertThat(validator.expectedDomains("device offline alarm"))
                .isEqualTo(Set.of(ExpertDomain.DEVICE));
    }

    @Test
    void genericDeviceAlertQuestionsDoNotRequireTheSecurityExpert() {
        // 冷机离线告警 is a device alert; requiring SECURITY would dispatch an
        // expert whose only tool looks up security events.
        assertThat(validator.expectedDomains("冷机离线告警为什么发生"))
                .isEqualTo(Set.of(ExpertDomain.DEVICE));
        // Security-specific alert phrases still select SECURITY.
        assertThat(validator.expectedDomains("安防告警有多少条"))
                .isEqualTo(Set.of(ExpertDomain.SECURITY));
    }

    @Test
    void rejectsAssignmentsThatReplaceTheOriginalQuestionScope() {
        var plan = new com.example.smartpark.collaboration.model.SupervisorPlan(
                "is device D1 offline?", Set.of(ExpertDomain.DEVICE),
                Map.of(ExpertDomain.DEVICE, "inspect device D2"), "device status");

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(SupervisorPlanValidator.SupervisorPlanValidationException.class)
                .hasMessageContaining("assignment");
    }
}
