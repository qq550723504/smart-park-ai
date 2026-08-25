package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorPlannerTest {
    private final SupervisorPlanner planner = new SupervisorPlanner();

    @Test void parsesAndValidatesAPlan() {
        var plan = planner.parseAndValidate("设备离线", """
                {"normalizedQuestion":"设备离线","selectedDomains":["DEVICE"],"assignments":{"DEVICE":"查设备状态"},"selectionReason":"设备状态问题"}
                """);
        assertThat(plan.selectedDomains()).containsExactly(ExpertDomain.DEVICE);
    }

    @Test void rejectsIllegalDomainAndMissingTaskCoverage() {
        assertThatThrownBy(() -> planner.parseAndValidate("设备离线", "{\"normalizedQuestion\":\"设备离线\",\"selectedDomains\":[\"UNKNOWN\"],\"assignments\":{},\"selectionReason\":\"x\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> planner.parseAndValidate("能耗和设备", "{\"normalizedQuestion\":\"能耗和设备\",\"selectedDomains\":[\"ENERGY\"],\"assignments\":{\"ENERGY\":\"查能耗\"},\"selectionReason\":\"x\"}"))
                .isInstanceOf(SupervisorPlanValidator.SupervisorPlanValidationException.class);
    }
}
