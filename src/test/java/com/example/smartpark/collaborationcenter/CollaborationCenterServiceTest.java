package com.example.smartpark.collaborationcenter;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationCenterServiceTest {

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
        assertThat(alert.safeSummary()).doesNotContain("raw diagnosis", "approval comment");
        assertThat(alert.safeSummary()).contains("ALT-POWER-001", "A2", "DEV-POWER-001");
        assertThat(items.get(1).detailPath()).isEqualTo("customer");
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
}
