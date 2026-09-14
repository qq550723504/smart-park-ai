package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventLocation;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecurityPrivacyMetadata;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.security.SecurityEventCatalog;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.port.security.SecuritySourceDescriptor;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.security.SecurityQueryTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityWorkflowTest {

    private static final Instant BASE = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void accessAlertPerformsRedactedReviewAndRequiresHumanApproval() {
        MockParkFixture fixture = new MockParkFixture();
        WorkflowEventPublisher events = WorkflowEventPublisher.inMemory();
        AlertWorkflow workflow = workflow(fixture, fixture.alerts(), fixture.security(), events);

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

    @Test
    void accessAlertReviewsTheReferencedSourceWhenAdaptersReuseTheEventId() {
        MockParkFixture fixture = new MockParkFixture();
        Alert original = fixture.alerts().getAlert("ALT-ACCESS-001");
        SecuritySourceRef accessSource = new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEventIdentity identity = new SecurityEventIdentity(accessSource, "SEC-ACCESS-001", original.parkId(),
                original.buildingId());
        SecurityEvent access = event("SEC-ACCESS-001", identity, "REDACTED: access-control source review",
                original.parkId(), original.buildingId(), BASE.plusSeconds(30));
        SecurityEvent camera = event("SEC-ACCESS-001",
                new SecurityEventIdentity(new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"),
                        "SEC-ACCESS-001", original.parkId(), original.buildingId()),
                "REDACTED: camera source review", original.parkId(), original.buildingId(), BASE.plusSeconds(120));
        SecurityEventCatalog catalog = new SecurityEventCatalog(fixture.security(),
                List.of(adapter(access), adapter(camera)));
        Alert qualified = new Alert(original.id(), original.parkId(), original.buildingId(), original.deviceId(),
                original.classification(), original.riskHint(), original.summary(), original.occurredAt(),
                List.of(identity.reference()));
        AlertPort alerts = new AlertPort() {
            @Override
            public Alert getAlert(String alertId) {
                return "ALT-ACCESS-001".equals(alertId) ? qualified : fixture.alerts().getAlert(alertId);
            }

            @Override
            public List<Alert> findHistory(String deviceId) {
                return fixture.alerts().findHistory(deviceId);
            }

            @Override
            public List<Alert> listActive() {
                return fixture.alerts().listActive();
            }
        };

        WorkflowSnapshot result = workflow(fixture, alerts, catalog, WorkflowEventPublisher.inMemory())
                .start("ALT-ACCESS-001");

        assertThat(result.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(result.statePayload().get(AlertWorkflowState.SCENARIO_ANALYSIS).toString())
                .contains("REDACTED: access-control source review")
                .doesNotContain("camera source review");
    }

    private static AlertWorkflow workflow(MockParkFixture fixture, AlertPort alerts,
                                          com.example.smartpark.port.security.SecurityPort security,
                                          WorkflowEventPublisher events) {
        return new AlertWorkflow(
                new AlertTriageAgent(new TestChatModel("""
                        {"category":"ACCESS","priority":"HIGH","riskLevel":"HIGH","confidence":0.97}
                        """)),
                new AlertDiagnosisAgent(
                        new TestChatModel("""
                                {
                                  "riskLevel":"HIGH",
                                  "rootCause":"Repeated denied access outside opening hours requires operator review.",
                                  "summary":"The redacted event indicates three denied attempts.",
                                  "evidence":["security: redacted rule match only","knowledge: require human review"],
                                  "recommendedAction":"Notify authorized security staff and create a review work order after approval.",
                                  "confidence":0.92
                                }
                                """),
                        new DeviceQueryTool(fixture.devices()),
                        new AlertQueryTool(fixture.alerts()),
                        new WorkOrderTool(fixture.workOrders()),
                        new ParkKnowledgeTool(fixture.knowledge()),
                        new EnergyQueryTool(fixture.energy()),
                        new SecurityQueryTool(fixture.security())),
                fixture.devices(),
                alerts,
                fixture.workOrders(),
                fixture.knowledge(),
                WorkflowExecutionStore.inMemory(),
                events,
                fixture.energy(),
                security);
    }

    private static SecurityEvent event(String eventId, SecurityEventIdentity identity, String evidenceSummary,
                                       String parkId, String buildingId, Instant receivedAt) {
        return new SecurityEvent(eventId, parkId, buildingId, SecurityEventType.ACCESS_ANOMALY,
                "UNAUTHORIZED_ACCESS_ATTEMPT", identity.source(), SecurityEventLocation.empty(), BASE, receivedAt,
                SecurityEventSeverity.UNKNOWN, null, SecurityPrivacyMetadata.redactedOnly(),
                SecurityDispositionRecord.unreviewed(), "test", null, evidenceSummary);
    }

    private static SecuritySourceAdapter adapter(SecurityEvent... events) {
        List<SecurityEvent> all = List.of(events);
        return new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("test-adapter", all.get(0).source().sourceType(), Set.of(), false);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return all;
            }
        };
    }
}
