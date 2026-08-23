package com.example.smartpark.agent;

import com.example.smartpark.model.Alert;
import com.example.smartpark.model.AlertClassification;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.KnowledgeDocument;
import com.example.smartpark.model.ParkContext;
import com.example.smartpark.model.RiskLevel;
import com.example.smartpark.park.mock.MockParkSystem;
import com.example.smartpark.tool.AlertQueryTool;
import com.example.smartpark.tool.DeviceQueryTool;
import com.example.smartpark.tool.ParkKnowledgeTool;
import com.example.smartpark.tool.WorkOrderTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDiagnosisAgentTest {

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
                  "diagnosedAt":"2026-08-23T01:30:00Z"
                }
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkSystem()),
                new AlertQueryTool(new MockParkSystem()),
                new WorkOrderTool(new MockParkSystem()),
                new ParkKnowledgeTool(new MockParkSystem()));

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), sampleKnowledge());

        assertThat(result.rootCause()).contains("filter");
        assertThat(model.lastPrompt().getUserMessage().getText())
                .contains("HVAC Supply Unit")
                .contains("Prior HVAC temperature warning")
                .contains("HVAC overheating playbook");
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
                  "diagnosedAt":"2026-08-23T01:35:00Z"
                }
                """);

        AlertDiagnosisAgent agent = new AlertDiagnosisAgent(
                model,
                new DeviceQueryTool(new MockParkSystem()),
                new AlertQueryTool(new MockParkSystem()),
                new WorkOrderTool(new MockParkSystem()),
                new ParkKnowledgeTool(new MockParkSystem()));

        Diagnosis result = agent.diagnose(sampleAlert(), sampleContext(), List.of());

        assertThat(result.evidence()).containsExactly("INSUFFICIENT_EVIDENCE: no knowledge documents matched the request");
        assertThat(model.lastPrompt().getUserMessage().getText()).contains("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void diagnosisToolListDoesNotExposeCreateWorkOrder() {
        MockParkSystem parkSystem = new MockParkSystem();
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
                          "diagnosedAt":"2026-08-23T01:40:00Z"
                        }
                        """),
                new DeviceQueryTool(parkSystem),
                new AlertQueryTool(parkSystem),
                new WorkOrderTool(parkSystem),
                new ParkKnowledgeTool(parkSystem));

        List<String> toolNames = java.util.Arrays.stream(agent.toolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(org.springframework.ai.tool.definition.ToolDefinition::name)
                .toList();

        assertThat(toolNames).contains("lookupDeviceStatus", "lookupAlertHistory", "lookupWorkOrders", "searchParkKnowledge");
        assertThat(toolNames).doesNotContain("createWorkOrder");
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
        MockParkSystem parkSystem = new MockParkSystem();
        return new ParkContext(
                "PARK-A",
                "A1",
                parkSystem.getDevice("DEV-HVAC-001"),
                parkSystem.findHistory("DEV-HVAC-001"),
                parkSystem.findByWorkflowId("wf-missing"));
    }

    private static List<KnowledgeDocument> sampleKnowledge() {
        return List.of(new KnowledgeDocument(
                "KD-OVERHEAT-001",
                "HVAC overheating playbook",
                "When HVAC supply temperatures rise, check filters, airflow, and compressor load before escalating.",
                List.of("overheating", "hvac", "temperature"),
                Instant.parse("2026-08-20T00:00:00Z")));
    }
}
