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

    @Test void rejectsIllegalDomain() {
        assertThatThrownBy(() -> planner.parseAndValidate("设备离线", "{\"normalizedQuestion\":\"设备离线\",\"selectedDomains\":[\"UNKNOWN\"],\"assignments\":{},\"selectionReason\":\"x\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesEveryExpertAssignmentFromTheOriginalQuestion() {
        var plan = planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":{"DEVICE":"inspect device D2"},"selectionReason":"device status"}
                """);

        assertThat(plan.assignments().get(ExpertDomain.DEVICE))
                .isEqualTo("is device D1 offline?")
                .doesNotContain("D2");
    }

    @Test
    void canonicalizesModelDomainAliasesBeforeValidation() {
        var plan = planner.parseAndValidate("电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联", """
                {"normalizedQuestion":"电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联",
                 "selectedDomains":["energy","power"],
                 "assignments":{"energy":"能耗分析","power":"设备分析"},
                 "selectionReason":"同时涉及能耗和设备"}
                """);

        assertThat(plan.selectedDomains()).containsExactly(ExpertDomain.ENERGY, ExpertDomain.DEVICE);
        assertThat(plan.assignments()).containsOnlyKeys(ExpertDomain.ENERGY, ExpertDomain.DEVICE);
    }

    @Test
    void canonicalizesEntityIdentifiersMisclassifiedAsDomains() {
        var plan = planner.parseAndValidate("电表 DEV-ENERGY-001 与安防事件 SEC-ACCESS-001 是否关联", """
                {"normalizedQuestion":"电表 DEV-ENERGY-001 与安防事件 SEC-ACCESS-001 是否关联",
                 "selectedDomains":["DEV-ENERGY-001","SEC-ACCESS-001"],
                 "assignments":{"DEV-ENERGY-001":"能耗分析","SEC-ACCESS-001":"安防分析"},
                 "selectionReason":"涉及能耗和安防"}
                """);

        assertThat(plan.selectedDomains()).containsExactly(ExpertDomain.ENERGY, ExpertDomain.SECURITY);
        assertThat(plan.assignments()).containsOnlyKeys(ExpertDomain.ENERGY, ExpertDomain.SECURITY);
    }

    @Test
    void resolvesPowerAliasFromQuestionContext() {
        var energyPlan = planner.parseAndValidate("power consumption for meter MTR-2", """
                {"normalizedQuestion":"power consumption for meter MTR-2",
                 "selectedDomains":["power"],
                 "assignments":{"power":"查能耗"},
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }

    @Test
    void prefersEnergyContextForPowerAliasWithEnergyMeterIdentifier() {
        var energyPlan = planner.parseAndValidate("power consumption for DEV-ENERGY-001", """
                {"normalizedQuestion":"power consumption for DEV-ENERGY-001",
                 "selectedDomains":["power"],
                 "assignments":{"power":"查能耗"},
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }
}
