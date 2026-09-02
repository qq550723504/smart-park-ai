package com.example.smartpark.collaborationcenter;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.port.customer.CustomerTicketReader;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationCenterServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), java.time.ZoneOffset.UTC);

    @Test
    void projectsSecurityIncidentHandoffWithoutChangingExistingSources() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of());
        when(tickets.list()).thenReturn(List.of());
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncident incident = securityIncident();
        SecurityIncidentHandoff handoff = handoffs.createOrGet(incident, TEST_CLOCK.instant());

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, tickets, TEST_CLOCK,
                new CollaborationSlaSnapshotStore(), handoffs).list(WorkItemQuery.defaults());

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(handoff.workItemId());
            assertThat(item.source()).isEqualTo(CollaborationWorkItem.Source.SECURITY_INCIDENT);
            assertThat(item.priority()).isEqualTo(CollaborationWorkItem.Priority.HIGH);
            assertThat(item.detailPath()).isEqualTo("/security/incidents/INC-1");
        });
    }

    @Test
    void projectsAlertAndCustomerTicketWithoutLeakingDomainObjects() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(alertSnapshot()));
        when(tickets.list()).thenReturn(List.of(new CustomerTicket(
                "cs-1", "session-1", "REPAIR", "WAITING_AGENT", "A1 洗手间漏水，等待客服接入。",
                Instant.EPOCH)));

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, tickets)
                .list(WorkItemQuery.defaults());

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("ALERT_WORKFLOW:wf-1", "CUSTOMER_TICKET:cs-1");
        CollaborationWorkItem alert = items.get(0);
        assertThat(alert.status()).isEqualTo(CollaborationWorkItem.Status.WAITING_APPROVAL);
        assertThat(alert.priority()).isEqualTo(CollaborationWorkItem.Priority.HIGH);
        assertThat(alert.detailPath()).isEqualTo("workflow");
        assertThat(alert.openedAt()).isEqualTo(Instant.EPOCH);
        assertThat(alert.slaDueAt()).isEqualTo(Instant.parse("1970-01-01T00:30:00Z"));
        assertThat(alert.slaState()).isEqualTo(CollaborationWorkItem.SlaState.OVERDUE);
        assertThat(alert.safeSummary()).doesNotContain("raw diagnosis", "approval comment");
        assertThat(alert.safeSummary()).contains("ALT-POWER-001", "A2", "DEV-POWER-001");
        assertThat(items.get(1).detailPath()).isEqualTo("customer");
        assertThat(items.get(1).openedAt()).isEqualTo(Instant.EPOCH);
        assertThat(items.get(1).slaDueAt()).isEqualTo(Instant.parse("1970-01-01T04:00:00Z"));
        assertThat(items.get(1).slaState()).isEqualTo(CollaborationWorkItem.SlaState.OVERDUE);
    }

    @Test
    void filtersBySourceAndCapsResultsAtTheRequestedLimit() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(alertSnapshot()));
        when(tickets.list()).thenReturn(List.of(
                new CustomerTicket("cs-1", "session-1", "REPAIR", "WAITING_AGENT", "维修请求一。",
                        Instant.parse("2026-09-01T08:00:00Z")),
                new CustomerTicket("cs-2", "session-2", "VISITOR", "ASSIGNED", "访客请求二。",
                        Instant.parse("2026-09-01T09:00:00Z"))));

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, tickets)
                .list(new WorkItemQuery(CollaborationWorkItem.Source.CUSTOMER_TICKET, null, 1));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("CUSTOMER_TICKET:cs-2");
    }

    @Test
    void samplesTheCompleteQueueBeforeApplyingFilters() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(alertSnapshot()));
        when(tickets.list()).thenReturn(List.of(new CustomerTicket(
                "cs-1", "session-1", "REPAIR", "WAITING_AGENT", "维修请求一。", Instant.EPOCH)));
        CollaborationSlaSnapshotStore snapshots = new CollaborationSlaSnapshotStore();

        new CollaborationCenterService(workflows, tickets, TEST_CLOCK, snapshots)
                .list(new WorkItemQuery(CollaborationWorkItem.Source.CUSTOMER_TICKET, null, 1,
                        WorkItemQuery.SortMode.SLA));

        assertThat(snapshots.list(60)).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.total()).isEqualTo(2);
            assertThat(snapshot.overdue()).isEqualTo(2);
        });
    }

    @Test
    void remainsAvailableWhenAlertWorkflowIsDisabled() {
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(tickets.list()).thenReturn(List.of(new CustomerTicket(
                "cs-offline", "session-offline", "REPAIR", "WAITING_AGENT", "维修请求。", Instant.EPOCH)));

        List<CollaborationWorkItem> items = new CollaborationCenterService(null, tickets)
                .list(WorkItemQuery.defaults());

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("CUSTOMER_TICKET:cs-offline");
    }

    @Test
    void projectsLocationFromNestedAlertAndParkContextSnapshotPayload() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(new WorkflowSnapshot(
                "wf-nested", "ALT-NESTED", WorkflowStatus.WAITING_APPROVAL,
                Map.of(
                        "alert", Map.of("parkId", "PARK-NESTED", "buildingId", "B7", "deviceId", "DEV-NESTED"),
                        "parkContext", Map.of(
                                "parkId", "PARK-NESTED", "buildingId", "B7",
                                "device", Map.of("id", "DEV-NESTED"))),
                null, Optional.empty(), null, List.of(), 1)));
        when(tickets.list()).thenReturn(List.of());

        CollaborationWorkItem item = new CollaborationCenterService(workflows, tickets)
                .list(WorkItemQuery.defaults()).get(0);

        assertThat(item.parkId()).isEqualTo("PARK-NESTED");
        assertThat(item.buildingId()).isEqualTo("B7");
        assertThat(item.deviceId()).isEqualTo("DEV-NESTED");
    }

    @Test
    void usesAlertOccurrenceAsWorkflowUpdateTimeBeforeDiagnosisExists() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        Instant occurredAt = Instant.parse("2026-09-01T10:15:00Z");
        when(workflows.snapshots()).thenReturn(List.of(new WorkflowSnapshot(
                "wf-running", "ALT-RUNNING", WorkflowStatus.RUNNING,
                Map.of("alert", Map.of("occurredAt", occurredAt.toString())),
                null, Optional.empty(), null, List.of(), 1)));
        when(tickets.list()).thenReturn(List.of());

        CollaborationWorkItem item = new CollaborationCenterService(workflows, tickets)
                .list(WorkItemQuery.defaults()).get(0);

        assertThat(item.updatedAt()).isEqualTo(occurredAt);
    }

    @Test
    void usesWorkflowCreationTimeAsOpenedAtBeforeAlertOccurrenceExists() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        Instant createdAt = Instant.parse("2026-09-01T08:00:00Z");
        when(workflows.snapshots()).thenReturn(List.of(new WorkflowSnapshot(
                "wf-created", "ALT-CREATED", WorkflowStatus.RUNNING,
                Map.of("createdAt", createdAt.toString(), "updatedAt", "2026-09-01T09:00:00Z"),
                null, Optional.empty(), null, List.of(), 1)));
        when(tickets.list()).thenReturn(List.of());

        CollaborationWorkItem item = new CollaborationCenterService(workflows, tickets, TEST_CLOCK)
                .list(WorkItemQuery.defaults()).get(0);

        assertThat(item.openedAt()).isEqualTo(createdAt);
        assertThat(item.slaDueAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    }

    @Test
    void preservesHighPriorityFromOriginalAlertAndClassificationSignals() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(new WorkflowSnapshot(
                "wf-high-signal", "ALT-HIGH-SIGNAL", WorkflowStatus.RUNNING,
                Map.of(
                        "riskLevel", "LOW",
                        "classification", Map.of("riskLevel", "HIGH"),
                        "alert", Map.of("riskHint", "LOW")),
                new Diagnosis("diag-low", "ALT-HIGH-SIGNAL", "DEV-1", RiskLevel.LOW,
                        "cause", "summary", List.of(), "action", 0.9, Instant.EPOCH),
                Optional.empty(), null, List.of(), 2)));
        when(tickets.list()).thenReturn(List.of());

        CollaborationWorkItem item = new CollaborationCenterService(workflows, tickets)
                .list(WorkItemQuery.defaults()).get(0);

        assertThat(item.priority()).isEqualTo(CollaborationWorkItem.Priority.HIGH);
    }

    @Test
    void projectsOnlyTheCurrentWorkflowAttemptForAnAlert() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(
                new WorkflowSnapshot("wf-failed", "ALT-RETRY", WorkflowStatus.FAILED,
                        Map.of("updatedAt", "2026-09-01T08:00:00Z"), null, Optional.empty(), null, List.of(), 3),
                new WorkflowSnapshot("wf-running", "ALT-RETRY", WorkflowStatus.RUNNING,
                        Map.of("updatedAt", "2026-09-01T09:00:00Z"), null, Optional.empty(), null, List.of(), 1)));
        when(tickets.list()).thenReturn(List.of());

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, tickets, TEST_CLOCK)
                .list(WorkItemQuery.defaults());

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("ALERT_WORKFLOW:wf-running");
    }

    @Test
    void readsCustomerItemsThroughTheLifecycleAwareReader() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketReader activeTickets = () -> List.of(new CustomerTicket(
                "cs-active", "session-active", "REPAIR", "WAITING_AGENT", "当前工单。", Instant.EPOCH));

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, activeTickets)
                .list(new WorkItemQuery(CollaborationWorkItem.Source.CUSTOMER_TICKET, null, 50));

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("CUSTOMER_TICKET:cs-active");
    }

    @Test
    void appliesSlaOrderingBeforeLimitingTheQueue() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(
                new WorkflowSnapshot("wf-overdue", "ALT-OVERDUE", WorkflowStatus.RUNNING,
                        Map.of("createdAt", "2026-09-02T06:00:00Z", "updatedAt", "2026-09-02T07:00:00Z"),
                        null, Optional.empty(), null, List.of(), 1),
                new WorkflowSnapshot("wf-on-track", "ALT-ON-TRACK", WorkflowStatus.RUNNING,
                        Map.of("createdAt", "2026-09-02T09:00:00Z", "updatedAt", "2026-09-02T09:30:00Z"),
                        null, Optional.empty(), null, List.of(), 2)));
        when(tickets.list()).thenReturn(List.of());

        CollaborationCenterService service = new CollaborationCenterService(workflows, tickets, TEST_CLOCK);
        List<CollaborationWorkItem> items = service
                .list(new WorkItemQuery(null, null, 1, WorkItemQuery.SortMode.SLA));

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("ALERT_WORKFLOW:wf-overdue");

        assertThat(service.list(new WorkItemQuery(null, null, 1, WorkItemQuery.SortMode.UPDATED_AT)))
                .extracting(CollaborationWorkItem::id)
                .containsExactly("ALERT_WORKFLOW:wf-on-track");
    }

    @Test
    void ordersEqualActiveSlaStatesByDeadlineBeforeLimitingTheQueue() {
        WorkflowExecutionStore workflows = mock(WorkflowExecutionStore.class);
        CustomerTicketPort tickets = mock(CustomerTicketPort.class);
        when(workflows.snapshots()).thenReturn(List.of(
                new WorkflowSnapshot("wf-due-first", "ALT-DUE-FIRST", WorkflowStatus.RUNNING,
                        Map.of("createdAt", "2026-09-02T08:05:00Z", "updatedAt", "2026-09-02T09:00:00Z"),
                        null, Optional.empty(), null, List.of(), 1),
                new WorkflowSnapshot("wf-due-later", "ALT-DUE-LATER", WorkflowStatus.RUNNING,
                        Map.of("createdAt", "2026-09-02T08:20:00Z", "updatedAt", "2026-09-02T09:50:00Z"),
                        null, Optional.empty(), null, List.of(), 2)));
        when(tickets.list()).thenReturn(List.of());

        List<CollaborationWorkItem> items = new CollaborationCenterService(workflows, tickets, TEST_CLOCK)
                .list(new WorkItemQuery(null, null, 1, WorkItemQuery.SortMode.SLA));

        assertThat(items).extracting(CollaborationWorkItem::id)
                .containsExactly("ALERT_WORKFLOW:wf-due-first");
    }

    private static WorkflowSnapshot alertSnapshot() {
        return new WorkflowSnapshot(
                "wf-1", "ALT-POWER-001", WorkflowStatus.WAITING_APPROVAL,
                Map.of("buildingId", "A2", "deviceId", "DEV-POWER-001",
                        "rawDiagnosis", "raw diagnosis", "approvalComment", "approval comment"),
                new Diagnosis("diag-1", "ALT-POWER-001", "DEV-POWER-001", RiskLevel.HIGH,
                        "raw diagnosis", "raw diagnosis", List.of("secret evidence"),
                        "approval comment", 0.9, Instant.EPOCH),
                Optional.empty(), null, List.of(), 4);
    }

    private static SecurityIncident securityIncident() {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.OPEN, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe")), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }
}
