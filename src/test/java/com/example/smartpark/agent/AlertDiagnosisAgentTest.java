package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.ParkContext;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDiagnosisAgentTest {

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
                          "rootCause":"非工作时段存在异常负载",
                          "summary":"楼宇能耗高于基线。",
                          "evidence":["电表：当前能耗高于基线"],
                          "recommendedAction":"检查非工作时段暖通运行计划。",
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
                .contains("暖通空调送风机组")
                .contains("历史暖通温度预警")
                .contains("暖通系统过热处置手册");
    }

    @Test
    void emptyKnowledgeProducesEvidenceInsufficiencyInsteadOfFabricatedEvidence() {
        TestChatModel model = new TestChatModel("""
                {
                  "id":"diag-2",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"LOW",
                  "rootCause":"证据不足，无法确定根因",
                  "summary":"没有可用于可靠诊断的知识文档。",
                  "evidence":["INSUFFICIENT_EVIDENCE：没有匹配本次请求的知识文档"],
                  "recommendedAction":"补充遥测数据，并在采取措施前咨询技术人员。",
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

        assertThat(result.evidence()).containsExactly("INSUFFICIENT_EVIDENCE：没有匹配本次请求的知识文档");
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
        TestChatModel model = new TestChatModel("""
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
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riskLevel");
    }

    @Test
    void invalidDiagnosisTimestampFailsClosed() {
        TestChatModel model = new TestChatModel("""
                {
                  "id":"diag-6",
                  "alertId":"ALT-TEMP-001",
                  "deviceId":"DEV-HVAC-001",
                  "riskLevel":"LOW",
                  "rootCause":"Restricted airflow from a clogged filter",
                  "summary":"The HVAC unit likely needs filter inspection.",
                  "evidence":["history: repeated temperature warnings"],
                  "recommendedAction":"Inspect and replace the HVAC filter, then verify airflow.",
                  "confidence":0.9,
                  "diagnosedAt":"yesterday"
                }
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkFixture().devices()),
                new AlertQueryTool(new MockParkFixture().alerts()),
                new WorkOrderTool(new MockParkFixture().workOrders()),
                new ParkKnowledgeTool(new MockParkFixture().knowledge()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("diagnosedAt");
    }

    @ParameterizedTest
    @MethodSource("nonFiniteConfidences")
    void nonFiniteConfidenceIsRejectedAtTheStructuredOutputBoundary(double confidence) {
        JsonNode output = JsonNodeFactory.instance.objectNode().put("confidence", confidence);

        assertThatThrownBy(() -> invokeRequireConfidence(output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confidence");
    }

    private static double invokeRequireConfidence(JsonNode output) {
        try {
            Method method = AlertDiagnosisAgent.class.getDeclaredMethod(
                    "requireConfidence",
                    JsonNode.class,
                    String.class);
            method.setAccessible(true);
            return (double) method.invoke(null, output, "diagnosis");
        }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(exception.getCause());
        }
        catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Stream<Double> nonFiniteConfidences() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    private static Alert sampleAlert() {
        return new Alert(
                "ALT-TEMP-001",
                "PARK-A",
                "A1",
                "DEV-HVAC-001",
                AlertClassification.TEMPERATURE,
                RiskLevel.LOW,
                "暖通机房温度持续升高",
                Instant.parse("2026-08-23T00:15:00Z"),
                List.of("传感器：温度探头-01", "趋势：持续上升"));
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
                "暖通系统过热处置手册",
                "暖通送风温度升高时，应先检查滤网、风量和压缩机负载，再决定是否升级处置。",
                List.of("过热", "暖通", "温度", "hvac", "temperature"),
                Instant.parse("2026-08-20T00:00:00Z")));
    }
}
