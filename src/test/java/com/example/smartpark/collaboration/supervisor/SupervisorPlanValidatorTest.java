package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorPlanValidatorTest {
    private final SupervisorPlanValidator validator = new SupervisorPlanValidator();

    @Test void selectsOnlyTheDomainActuallyPresent() {
        assertThat(validator.expectedDomains("A2 night energy consumption increased")).containsExactly(ExpertDomain.ENERGY);
        assertThat(validator.expectedDomains("CHILLER-1 is offline")).containsExactly(ExpertDomain.DEVICE);
        assertThat(validator.expectedDomains("门禁发生安防告警")).containsExactly(ExpertDomain.SECURITY);
    }

    @Test void requiresAllThreeDomainsForCrossDomainQuestion() {
        String question = "A2 夜间能耗升高且门禁告警、冷机离线";
        assertThat(validator.expectedDomains(question)).containsExactlyInAnyOrder(ExpertDomain.ENERGY, ExpertDomain.DEVICE, ExpertDomain.SECURITY);
        SupervisorPlan incomplete = new SupervisorPlan(question, Set.of(ExpertDomain.ENERGY), Map.of(ExpertDomain.ENERGY, "energy"), "reason");
        assertThatThrownBy(() -> validator.validate(incomplete)).isInstanceOf(SupervisorPlanValidator.SupervisorPlanValidationException.class);
    }

    @Test void rejectsAmbiguousQuestionsInsteadOfDispatchingEveryExpert() {
        SupervisorPlan plan = new SupervisorPlan("please investigate", Set.of(ExpertDomain.ENERGY), Map.of(ExpertDomain.ENERGY, "investigate"), "guess");
        assertThatThrownBy(() -> validator.validate(plan)).hasMessageContaining("ambiguous");
    }
}
