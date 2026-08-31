package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void derivesEveryExecutableAssignmentFromTheOriginalQuestion() {
        var plan = planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":{"DEVICE":"inspect device D1 and explain its status"},"selectionReason":"device status"}
                """);

        assertThat(plan.assignments().get(ExpertDomain.DEVICE))
                .isEqualTo("is device D1 offline?")
                .doesNotContain("D2");
    }

    @Test
    void rejectsProviderNormalizedQuestionThatDoesNotMatchTheInput() {
        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D2 offline?","selectedDomains":["DEVICE"],
                 "assignments":{"DEVICE":"inspect device D1"},"selectionReason":"device status"}
                """))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("normalizedQuestion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"   \""})
    void rejectsNullOrBlankProviderAssignments(String assignmentJson) {
        String response = """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":{"DEVICE":%s},"selectionReason":"device status"}
                """.formatted(assignmentJson);

        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", response))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("assignment");
    }

    @Test
    void rejectsProviderAssignmentThatReplacesAnInputEntityIdentifier() {
        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":{"DEVICE":"inspect device D2"},"selectionReason":"device status"}
                """))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("D1");
    }

    @Test
    void canonicalizesModelDomainAliasesBeforeValidation() {
        var plan = planner.parseAndValidate("电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联", """
                {"normalizedQuestion":"电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联",
                 "selectedDomains":["energy","power"],
                 "assignments":{"energy":"分析 DEV-ENERGY-001 与 DEV-POWER-001 的能耗关系",
                                "power":"分析 DEV-ENERGY-001 与 DEV-POWER-001 的设备关系"},
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
                 "assignments":{"DEV-ENERGY-001":"分析 DEV-ENERGY-001 与 SEC-ACCESS-001 的能耗关系",
                                "SEC-ACCESS-001":"分析 DEV-ENERGY-001 与 SEC-ACCESS-001 的安防关系"},
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
                 "assignments":{"power":"查询 MTR-2 的能耗"},
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }

    @Test
    void prefersEnergyContextForPowerAliasWithEnergyMeterIdentifier() {
        var energyPlan = planner.parseAndValidate("power consumption for DEV-ENERGY-001", """
                {"normalizedQuestion":"power consumption for DEV-ENERGY-001",
                 "selectedDomains":["power"],
                 "assignments":{"power":"查询 DEV-ENERGY-001 的能耗"},
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }
}
