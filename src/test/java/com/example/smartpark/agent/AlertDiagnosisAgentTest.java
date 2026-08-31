package com.example.smartpark.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.security.SecurityQueryTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDiagnosisAgentTest {

    @Test
    void providerShapedSemanticDiagnosisGetsServerOwnedIdentity() {
        String providerResponse = """
                {
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings","knowledge: check filters first"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.88
                }
                """;
        TestChatModel model = new TestChatModel(providerResponse, providerResponse);
        AlertDiagnosisAgent agent = agent(model);
        Instant startedAt = Instant.now();

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(result.id()).isNotBlank();
        assertThat(result.alertId()).isEqualTo("ALT-TEMP-001");
        assertThat(result.deviceId()).isEqualTo("DEV-HVAC-001");
        assertThat(result.diagnosedAt()).isBetween(startedAt, Instant.now());
        assertThat(result.rootCause()).contains("filter");
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void diagnosisRequestUsesStrictProviderSchemaForOnlyModelOwnedFields() {
        TestChatModel model = new TestChatModel("""
                {
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings"],
                  "recommendedAction":"Inspect and replace the HVAC filter.",
                  "confidence":0.88
                }
                """);
        AlertDiagnosisAgent agent = agent(model);

        agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(model.lastPrompt().getOptions()).isInstanceOf(DashScopeChatOptions.class);
        DashScopeResponseFormat responseFormat = ((DashScopeChatOptions) model.lastPrompt().getOptions())
                .getResponseFormat();
        assertThat(responseFormat.getType()).isEqualTo(DashScopeResponseFormat.Type.JSON_SCHEMA);
        assertThat(responseFormat.getJsonScheme().getStrict()).isTrue();
        assertThat(responseFormat.getJsonScheme().getName()).isEqualTo("alert_diagnosis");
        assertThat(responseFormat.getJsonScheme().getSchema()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) responseFormat.getJsonScheme().getSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "riskLevel", "rootCause", "summary", "evidence", "recommendedAction", "confidence");
        assertThat(properties).doesNotContainKeys("id", "alertId", "deviceId", "diagnosedAt");
        assertThat(properties.get("confidence")).asString().contains("type=number");
    }

    @Test
    void diagnosisExposesReadOnlyEnergyConsumptionLookup() {
        MockParkFixture parkSystem = new MockParkFixture();
        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                new TestChatModel("""
                        {
                          "id":"diag-energy-1",
                          "alertId":"ALT-ENERGY-001",
                          "deviceId":"DEV-ENERGY-001",
                          "riskLevel":"HIGH",
                          "rootCause":"Unexpected after-hours load",
                          "summary":"The building consumed more energy than its baseline.",
                          "evidence":["meter: current consumption is above baseline"],
                          "recommendedAction":"Inspect after-hours HVAC schedules.",
                          "confidence":0.9,
                          "diagnosedAt":"2026-08-23T01:45:00Z"
                        }
                        """),
                new DeviceQueryTool(parkSystem.devices()),
                new AlertQueryTool(parkSystem.alerts()),
                new WorkOrderTool(parkSystem.workOrders()),
                new ParkKnowledgeTool(parkSystem.knowledge()),
                new EnergyQueryTool(parkSystem.energy()));

        List<String> toolNames = java.util.Arrays.stream(agent.toolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(org.springframework.ai.tool.definition.ToolDefinition::name)
                .toList();

        assertThat(toolNames).contains("lookupEnergyConsumption");
        assertThat(toolNames).doesNotContain("createWorkOrder");
    }

    @Test
    void diagnosisExposesOnlyRedactedSecurityEventLookup() {
        MockParkFixture parkSystem = new MockParkFixture();
        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                new TestChatModel("""
                        {
                          "id":"diag-security-1",
                          "alertId":"ALT-ACCESS-001",
                          "deviceId":"DEV-ACCESS-001",
                          "riskLevel":"HIGH",
                          "rootCause":"Repeated denied access",
                          "summary":"An authorized operator should review the redacted event.",
                          "evidence":["security-event: redacted summary only"],
                          "recommendedAction":"Review without retrieving raw identity or media.",
                          "confidence":0.9,
                          "diagnosedAt":"2026-08-23T01:46:00Z"
                        }
                        """),
                new DeviceQueryTool(parkSystem.devices()),
                new AlertQueryTool(parkSystem.alerts()),
                new WorkOrderTool(parkSystem.workOrders()),
                new ParkKnowledgeTool(parkSystem.knowledge()),
                new EnergyQueryTool(parkSystem.energy()),
                new SecurityQueryTool(parkSystem.security()));

        List<String> toolNames = java.util.Arrays.stream(agent.toolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(org.springframework.ai.tool.definition.ToolDefinition::name)
                .toList();

        assertThat(toolNames).contains("lookupSecurityEvent");
        assertThat(toolNames).doesNotContain("createWorkOrder");
    }

    @Test
    void diagnosisPromptIncludesParkContextAndKnowledgeContent() {
        TestChatModel model = new TestChatModel("""
                {
                  "id":"diag-1",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings","knowledge: check filters first"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.88,
                  "diagnosedAt":"2026-08-23T01:30:00Z"
                }
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(result.rootCause()).contains("filter");
        assertThat(result.confidence()).isEqualTo(0.88);
        assertThat(model.lastPrompt().getUserMessage().getText())
                .contains("HVAC Supply Unit")
                .contains("Prior HVAC temperature warning")
                .contains("HVAC overheating playbook");
    }

    @Test
    void diagnosisRetriesOnceWhenTheFirstModelResponseViolatesTheJsonContract() {
        TestChatModel model = new TestChatModel(
                "not-json",
                """
                {"id":"diag-retry","alertId":"ALT-TEMP-001","deviceId":"DEV-HVAC-001","riskLevel":"LOW","rootCause":"Restricted airflow from a clogged filter","summary":"The HVAC unit likely needs filter inspection.","evidence":["history: repeated temperature warnings"],"recommendedAction":"Inspect and replace the HVAC filter.","confidence":0.88,"diagnosedAt":"2026-08-23T01:30:00Z"}
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(result.id()).isNotBlank().isNotEqualTo("diag-retry");
        assertThat(result.alertId()).isEqualTo("ALT-TEMP-001");
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(model.lastPrompt().getSystemMessage().getText())
                .contains("exactly one JSON object")
                .contains("Markdown fences");
    }

    @Test
    void emptyKnowledgeProducesEvidenceInsufficiencyInsteadOfFabricatedEvidence() {
        TestChatModel model = new TestChatModel("""
                {
                  "id":"diag-2",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"LOW",
                  "rootCause":"Insufficient evidence to determine the root cause",
                  "summary":"No supporting knowledge documents were available for a confident diagnosis.",
                  "evidence":["INSUFFICIENT_EVIDENCE: no knowledge documents matched the request"],
                  "recommendedAction":"Collect additional telemetry and consult a technician before acting.",
                  "confidence":0.31,
                  "diagnosedAt":"2026-08-23T01:35:00Z"
                }
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), List.of());

        assertThat(result.evidence()).containsExactly("INSUFFICIENT_EVIDENCE: no knowledge documents matched the request");
        assertThat(model.lastPrompt().getUserMessage().getText()).contains("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void diagnosisToolListDoesNotExposeCreateWorkOrder() {
        MockParkFixture parkSystem = new MockParkFixture();
        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                new TestChatModel("""
                        {
                          "id":"diag-3",
                          "alertId":"ALT-TEMP-001",
                          "deviceId":"DEV-HVAC-001",
                          "riskLevel":"LOW",
                          "rootCause":"n/a",
                          "summary":"n/a",
                          "evidence":["INSUFFICIENT_EVIDENCE"],
                          "recommendedAction":"n/a",
                          "confidence":0.2,
                          "diagnosedAt":"2026-08-23T01:40:00Z"
                        }
                        """),
                new DeviceQueryTool(parkSystem.devices()),
                new AlertQueryTool(parkSystem.alerts()),
                new WorkOrderTool(parkSystem.workOrders()),
                new ParkKnowledgeTool(parkSystem.knowledge()));

        List<String> toolNames = java.util.Arrays.stream(agent.toolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(org.springframework.ai.tool.definition.ToolDefinition::name)
                .toList();

        assertThat(toolNames).contains("lookupDeviceStatus", "lookupAlertHistory", "lookupWorkOrders", "searchParkKnowledge");
        assertThat(toolNames).doesNotContain("createWorkOrder");
    }

    @Test
    void diagnosisSuppliesReadOnlyToolCallbacksToTheModelRequest() {
        TestChatModel model = new TestChatModel("""
                {
                  "id":"diag-4",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings","knowledge: check filters first"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.91,
                  "diagnosedAt":"2026-08-23T01:45:00Z"
                }
                """);
        MockParkFixture parkSystem = new MockParkFixture();
        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(parkSystem.devices()),
                new AlertQueryTool(parkSystem.alerts()),
                new WorkOrderTool(parkSystem.workOrders()),
                new ParkKnowledgeTool(parkSystem.knowledge()));

        agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(model.lastPrompt().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.lastPrompt().getOptions();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("lookupDeviceStatus", "lookupAlert", "lookupAlertHistory", "lookupWorkOrders", "searchParkKnowledge")
                .doesNotContain("createWorkOrder");
    }

    @Test
    void invalidDiagnosisRiskLevelFailsClosed() {
        String content = """
                {
                  "id":"diag-5",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"SEVERE",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.9,
                  "diagnosedAt":"2026-08-23T01:50:00Z"
                }
                """;
        TestChatModel model = new TestChatModel(content, content);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyDiagnosisEvidenceFailsClosedAfterOneBoundedRetry() {
        String content = """
                {"riskLevel":"LOW","rootCause":"cause","summary":"summary","evidence":[],"recommendedAction":"action","confidence":0.5}
                """;
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> agent(model).diagnose(sampleAlert(), sampleContext(), sampleKnowledge()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    void modelSuppliedIdentityAndTimestampCannotOverrideServerOwnedValues() {
        String content = """
                {
                  "id":"provider-controlled-id",
                  "alertId":"provider-controlled-alert",
                  "deviceId":"provider-controlled-device",
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.9,
                  "diagnosedAt":"2000-01-01T00:00:00Z"
                }
                """;
        TestChatModel model = new TestChatModel(content);
        Instant startedAt = Instant.now();

        Diagnosis result = agent(model).diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(result.id()).isNotEqualTo("provider-controlled-id");
        assertThat(result.alertId()).isEqualTo("ALT-TEMP-001");
        assertThat(result.deviceId()).isEqualTo("DEV-HVAC-001");
        assertThat(result.diagnosedAt()).isBetween(startedAt, Instant.now());
    }

    @ParameterizedTest
    @MethodSource("invalidConfidences")
    void invalidConfidenceIsRejectedAtTheStructuredOutputBoundary(String confidence) {
        String content = """
                {"riskLevel":"LOW","rootCause":"cause","summary":"summary","evidence":["evidence"],"recommendedAction":"action","confidence":%s}
                """.formatted(confidence);
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> agent(model).diagnose(sampleAlert(), sampleContext(), sampleKnowledge()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Stream<String> invalidConfidences() {
        return Stream.of("-0.1", "1.1", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"");
    }

    private static AlertDiagnosisAgent agent(TestChatModel model) {
        MockParkFixture parkSystem = new MockParkFixture();
        return new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(parkSystem.devices()),
                new AlertQueryTool(parkSystem.alerts()),
                new WorkOrderTool(parkSystem.workOrders()),
                new ParkKnowledgeTool(parkSystem.knowledge()));
    }

    private static Alert sampleAlert() {
        return new Alert(
                "ALT-TEMP-001",
                "PARK-A",
                "A1",
                "DEV-HVAC-001",
                AlertClassification.TEMPERATURE,
                RiskLevel.LOW,
                "Temperature rising in HVAC room",
                Instant.parse("2026-08-23T00:15:00Z"),
                List.of("sensor:temp-01", "trend:upward"));
    }

    private static ParkContext sampleContext() {
        MockParkFixture parkSystem = new MockParkFixture();
        return new ParkContext(
                "PARK-A",
                "A1",
                parkSystem.devices().getDevice("DEV-HVAC-001"),
                parkSystem.alerts().findHistory("DEV-HVAC-001"),
                parkSystem.workOrders().findByWorkflowId("wf-missing"));
    }

    private static List<KnowledgeDocument> sampleKnowledge() {
        return List.of(new KnowledgeDocument(
                "KD-OVERHEAT-001",
                KnowledgeDomain.ALERT_OPERATIONS,
                "HVAC overheating playbook",
                "When HVAC supply temperatures rise, check filters, airflow, and compressor load before escalating.",
                List.of("overheating", "hvac", "temperature"),
                Instant.parse("2026-08-20T00:00:00Z")));
    }
}
