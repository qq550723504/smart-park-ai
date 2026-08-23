package com.example.smartpark.workflow;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyWorkflowTest {

    @Test
    void energyAnomalyUsesEnergyKnowledgeAndWaitsForApproval() {
        MockParkFixture fixture = new MockParkFixture();
        TestChatModel diagnosisModel = new TestChatModel("""
                {
                  "id":"diag-energy-workflow-1",
                  "alertId":"ALT-ENERGY-001",
                  "deviceId":"DEV-ENERGY-001",
                  "riskLevel":"HIGH",
                  "rootCause":"After-hours HVAC and lighting runtime exceeded the baseline.",
                  "summary":"Energy consumption is 38 percent above the building baseline.",
                  "evidence":["meter: current=138kWh baseline=100kWh","knowledge: inspect HVAC and lighting runtime"],
                  "recommendedAction":"Verify schedules and inspect HVAC and lighting runtime before corrective work.",
                  "confidence":0.91,
                  "diagnosedAt":"2026-08-23T01:45:00Z"
                }
                """);
        AlertWorkflow workflow = new AlertWorkflow(
                new AlertTriageAgent(new TestChatModel("""
                        {"category":"ENERGY","priority":"HIGH","riskLevel":"HIGH","confidence":0.96}
                        """)),
                new AlertDiagnosisAgent(
                        diagnosisModel,
                        new DeviceQueryTool(fixture.devices()),
                        new AlertQueryTool(fixture.alerts()),
                        new WorkOrderTool(fixture.workOrders()),
                        new ParkKnowledgeTool(fixture.knowledge()),
                        new EnergyQueryTool(fixture.energy())),
                fixture.devices(),
                fixture.alerts(),
                fixture.workOrders(),
                fixture.knowledge(),
                WorkflowExecutionStore.inMemory(),
                WorkflowEventPublisher.inMemory());

        WorkflowSnapshot result = workflow.start("ALT-ENERGY-001");

        assertThat(result.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(result.statePayload().get(AlertWorkflowState.RETRIEVED_DOCUMENTS).toString())
                .contains("KD-ENERGY-001");
        assertThat(result.diagnosis().summary()).contains("38 percent");
        assertThat(diagnosisModel.lastPrompt().getUserMessage().getText())
                .contains("Building A2 Energy Meter")
                .contains("Energy anomaly response playbook");
    }
}
