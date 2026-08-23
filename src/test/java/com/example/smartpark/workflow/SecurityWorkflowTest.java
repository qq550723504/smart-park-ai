package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.security.SecurityQueryTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityWorkflowTest {

    @Test
    void accessAlertPerformsRedactedReviewAndRequiresHumanApproval() {
        MockParkFixture fixture = new MockParkFixture();
        WorkflowEventPublisher events = WorkflowEventPublisher.inMemory();
        AlertWorkflow workflow = new AlertWorkflow(
                new AlertTriageAgent(new TestChatModel("""
                        {"category":"ACCESS","priority":"HIGH","riskLevel":"HIGH","confidence":0.97}
                        """)),
                new AlertDiagnosisAgent(
                        new TestChatModel("""
                                {
                                  "id":"diag-security-workflow-1",
                                  "alertId":"ALT-ACCESS-001",
                                  "deviceId":"DEV-ACCESS-001",
                                  "riskLevel":"HIGH",
                                  "rootCause":"Repeated denied access outside opening hours requires operator review.",
                                  "summary":"The redacted event indicates three denied attempts.",
                                  "evidence":["security: redacted rule match only","knowledge: require human review"],
                                  "recommendedAction":"Notify authorized security staff and create a review work order after approval.",
                                  "confidence":0.92,
                                  "diagnosedAt":"2026-08-23T01:50:00Z"
                                }
                                """),
                        new DeviceQueryTool(fixture.devices()),
                        new AlertQueryTool(fixture.alerts()),
                        new WorkOrderTool(fixture.workOrders()),
                        new ParkKnowledgeTool(fixture.knowledge()),
                        new EnergyQueryTool(fixture.energy()),
                        new SecurityQueryTool(fixture.security())),
                fixture.devices(),
                fixture.alerts(),
                fixture.workOrders(),
                fixture.knowledge(),
                WorkflowExecutionStore.inMemory(),
                events,
                fixture.energy(),
                fixture.security());

        WorkflowSnapshot result = workflow.start("ALT-ACCESS-001");

        assertThat(result.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(result.statePayload().get(AlertWorkflowState.SCENARIO_ANALYSIS).toString())
                .contains("SECURITY_REDACTED_REVIEW", "REDACTED:")
                .doesNotContain("base64", "data:image");
        assertThat(result.statePayload().get(AlertWorkflowState.RETRIEVED_DOCUMENTS).toString())
                .contains("KD-ACCESS-001");
        assertThat(events.events(result.workflowId())
                .filter(event -> event.node().equals(AlertWorkflowNodes.SECURITY_REVIEW)
                        && event.eventType() == WorkflowEvent.EventType.NODE_COMPLETED)
                .next()
                .block(Duration.ofSeconds(2)))
                .isNotNull();
        assertThat(events.events(result.workflowId())
                .filter(event -> event.node().equals(AlertWorkflowNodes.HUMAN_APPROVAL)
                        && event.eventType() == WorkflowEvent.EventType.PAUSED)
                .next()
                .block(Duration.ofSeconds(2)))
                .isNotNull();
    }
}
