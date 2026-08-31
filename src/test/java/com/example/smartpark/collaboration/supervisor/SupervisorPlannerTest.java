package com.example.smartpark.collaboration.supervisor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.smartpark.collaboration.model.ExpertDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorPlannerTest {
    private final SupervisorPlanner planner = new SupervisorPlanner();

    @Test
    void logsOnlyMissingRequiredFieldCodeWithoutProviderPayload() {
        ListAppender<ILoggingEvent> appender = capturePlannerLogs();
        String question = "QUESTION_SENTINEL DEV-SECRET-42";
        String modelOutput = """
                {"normalizedQuestion":"QUESTION_SENTINEL DEV-SECRET-42", "selectedDomains":["ENERGY"],
                 "assignments":{"ENERGY":"ASSIGNMENT_SENTINEL DEV-SECRET-42"}}
                """;
        try {
            assertThatThrownBy(() -> planner.parseAndValidate(question, modelOutput))
                    .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("MISSING_REQUIRED_FIELD");
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n")))
                    .doesNotContain("QUESTION_SENTINEL", "ASSIGNMENT_SENTINEL", "DEV-SECRET-42");
        } finally {
            releasePlannerLogs(appender);
        }
    }

    @ParameterizedTest
    @MethodSource("providerRejections")
    void logsTheBoundedCodeForEveryProviderRejection(String modelOutput, String rejectionCode) {
        ListAppender<ILoggingEvent> appender = capturePlannerLogs();
        try {
            assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", modelOutput))
                    .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(rejectionCode);
        } finally {
            releasePlannerLogs(appender);
        }
    }

    @Test void parsesAndValidatesAPlan() {
        var plan = planner.parseAndValidate("设备离线", """
                {"normalizedQuestion":"设备离线","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":"查设备状态"}],"selectionReason":"设备状态问题"}
                """);
        assertThat(plan.selectedDomains()).containsExactly(ExpertDomain.DEVICE);
    }

    @Test
    void parsesTypedProviderAssignmentItemsWithoutChangingServerOwnedRouting() {
        var plan = planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":"inspect device D1"}],
                 "selectionReason":"device status"}
                """);

        assertThat(plan.selectedDomains()).containsExactly(ExpertDomain.DEVICE);
        assertThat(plan.assignments()).containsEntry(ExpertDomain.DEVICE, "is device D1 offline?");
    }

    @Test void rejectsIllegalDomain() {
        assertThatThrownBy(() -> planner.parseAndValidate("设备离线", "{\"normalizedQuestion\":\"设备离线\",\"selectedDomains\":[\"UNKNOWN\"],\"assignments\":{},\"selectionReason\":\"x\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesEveryExecutableAssignmentFromTheOriginalQuestion() {
        var plan = planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":"inspect device D1 and explain its status"}],"selectionReason":"device status"}
                """);

        assertThat(plan.assignments().get(ExpertDomain.DEVICE))
                .isEqualTo("is device D1 offline?")
                .doesNotContain("D2");
    }

    @Test
    void rejectsProviderNormalizedQuestionThatDoesNotMatchTheInput() {
        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D2 offline?","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":"inspect device D1"}],"selectionReason":"device status"}
                """))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("normalizedQuestion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"   \""})
    void rejectsNullOrBlankProviderAssignments(String assignmentJson) {
        String response = """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":%s}],"selectionReason":"device status"}
                """.formatted(assignmentJson);

        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", response))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("assignment");
    }

    @Test
    void rejectsProviderAssignmentThatReplacesAnInputEntityIdentifier() {
        assertThatThrownBy(() -> planner.parseAndValidate("is device D1 offline?", """
                {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                 "assignments":[{"domain":"DEVICE","assignment":"inspect device D2"}],"selectionReason":"device status"}
                """))
                .isInstanceOf(com.example.smartpark.agent.ModelOutputException.class)
                .hasMessageContaining("D1");
    }

    @Test
    void canonicalizesModelDomainAliasesBeforeValidation() {
        var plan = planner.parseAndValidate("电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联", """
                {"normalizedQuestion":"电表 DEV-ENERGY-001 与设备 DEV-POWER-001 是否关联",
                 "selectedDomains":["energy","power"],
                 "assignments":[{"domain":"energy","assignment":"分析 DEV-ENERGY-001 与 DEV-POWER-001 的能耗关系"},
                                {"domain":"power","assignment":"分析 DEV-ENERGY-001 与 DEV-POWER-001 的设备关系"}],
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
                 "assignments":[{"domain":"DEV-ENERGY-001","assignment":"分析 DEV-ENERGY-001 与 SEC-ACCESS-001 的能耗关系"},
                                {"domain":"SEC-ACCESS-001","assignment":"分析 DEV-ENERGY-001 与 SEC-ACCESS-001 的安防关系"}],
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
                 "assignments":[{"domain":"power","assignment":"查询 MTR-2 的能耗"}],
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }

    @Test
    void prefersEnergyContextForPowerAliasWithEnergyMeterIdentifier() {
        var energyPlan = planner.parseAndValidate("power consumption for DEV-ENERGY-001", """
                {"normalizedQuestion":"power consumption for DEV-ENERGY-001",
                 "selectedDomains":["power"],
                 "assignments":[{"domain":"power","assignment":"查询 DEV-ENERGY-001 的能耗"}],
                 "selectionReason":"能耗问题"}
                """);

        assertThat(energyPlan.selectedDomains()).containsExactly(ExpertDomain.ENERGY);
    }

    private static ListAppender<ILoggingEvent> capturePlannerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(SupervisorPlanner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void releasePlannerLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(SupervisorPlanner.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static Stream<Arguments> providerRejections() {
        return Stream.of(
                Arguments.of("{MODEL_OUTPUT_SENTINEL", "MALFORMED_JSON"),
                Arguments.of("[]", "NON_OBJECT"),
                Arguments.of("{}", "EMPTY_OBJECT"),
                Arguments.of("""
                        {"normalizedQuestion":7,"selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "NORMALIZED_QUESTION_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D2 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "QUESTION_MISMATCH"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":{},
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "SELECTED_DOMAINS_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":[7],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "SELECTED_DOMAIN_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["UNKNOWN"],
                         "assignments":[{"domain":"UNKNOWN","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "DOMAIN"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":{},"selectionReason":"device status"}
                        """, "ASSIGNMENTS_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":["DEVICE"],"selectionReason":"device status"}
                        """, "ASSIGNMENT_ENTRY_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":7,"assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "ASSIGNMENT_DOMAIN_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":null}],"selectionReason":"device status"}
                        """, "ASSIGNMENT_TYPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D2"}],"selectionReason":"device status"}
                        """, "ASSIGNMENT_SCOPE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[],"selectionReason":"device status"}
                        """, "COVERAGE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"},
                                        {"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":"device status"}
                        """, "COVERAGE"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1","unexpected":true}],"selectionReason":"device status"}
                        """, "UNKNOWN_FIELD"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"unexpected":true,"selectionReason":"device status"}
                        """, "UNKNOWN_FIELD"),
                Arguments.of("""
                        {"normalizedQuestion":"is device D1 offline?","selectedDomains":["DEVICE"],
                         "assignments":[{"domain":"DEVICE","assignment":"inspect D1"}],"selectionReason":false}
                        """, "SELECTION_REASON_TYPE"));
    }
}
