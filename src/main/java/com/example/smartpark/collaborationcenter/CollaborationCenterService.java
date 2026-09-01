package com.example.smartpark.collaborationcenter;

import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CollaborationCenterService {
    private final WorkflowExecutionStore workflows;
    private final CustomerTicketPort tickets;

    public CollaborationCenterService(WorkflowExecutionStore workflows, CustomerTicketPort tickets) {
        this.workflows = workflows;
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    public List<CollaborationWorkItem> list(WorkItemQuery query) {
        WorkItemQuery requested = Objects.requireNonNull(query, "query");
        List<CollaborationWorkItem> items = new ArrayList<>();
        if (requested.source() == null || requested.source() == CollaborationWorkItem.Source.ALERT_WORKFLOW) {
            if (workflows != null) {
                workflows.snapshots().stream().map(CollaborationCenterService::fromWorkflow)
                        .filter(requested::accepts).forEach(items::add);
            }
        }
        if (requested.source() == null || requested.source() == CollaborationWorkItem.Source.CUSTOMER_TICKET) {
            tickets.list().stream().map(CollaborationCenterService::fromTicket)
                    .filter(requested::accepts).forEach(items::add);
        }
        return items.stream()
                .sorted(Comparator.comparing(CollaborationWorkItem::updatedAt).reversed()
                        .thenComparing(CollaborationWorkItem::id))
                .limit(requested.limit())
                .toList();
    }

    private static CollaborationWorkItem fromWorkflow(WorkflowSnapshot snapshot) {
        Map<String, Object> payload = snapshot.statePayload();
        String parkId = value(payload, "parkId");
        String buildingId = value(payload, "buildingId");
        String deviceId = value(payload, "deviceId");
        Diagnosis diagnosis = snapshot.diagnosis();
        if (diagnosis != null) {
            deviceId = diagnosis.deviceId();
        }
        if (snapshot.workOrder() != null) {
            parkId = snapshot.workOrder().parkId();
            buildingId = snapshot.workOrder().buildingId();
            deviceId = snapshot.workOrder().deviceId();
        }
        String location = join(" · ", buildingId, deviceId);
        String summary = location.isBlank()
                ? "告警 " + snapshot.alertId() + " · " + snapshot.status().name()
                : "告警 " + snapshot.alertId() + " · " + location;
        Instant updatedAt = snapshot.workOrder() == null
                ? (diagnosis == null ? Instant.EPOCH : diagnosis.diagnosedAt())
                : snapshot.workOrder().updatedAt();
        return new CollaborationWorkItem(
                "ALERT_WORKFLOW:" + snapshot.workflowId(),
                CollaborationWorkItem.Source.ALERT_WORKFLOW,
                CollaborationWorkItem.Status.valueOf(snapshot.status().name()),
                diagnosis != null && diagnosis.riskLevel() == RiskLevel.HIGH
                        ? CollaborationWorkItem.Priority.HIGH : CollaborationWorkItem.Priority.NORMAL,
                "告警处置 " + snapshot.alertId(), summary, parkId, buildingId, deviceId, updatedAt, "workflow");
    }

    private static CollaborationWorkItem fromTicket(CustomerTicket ticket) {
        return new CollaborationWorkItem(
                "CUSTOMER_TICKET:" + ticket.id(),
                CollaborationWorkItem.Source.CUSTOMER_TICKET,
                CollaborationWorkItem.Status.valueOf(CustomerTicketStatus.valueOf(ticket.status()).name()),
                CollaborationWorkItem.Priority.NORMAL,
                "客服工单 " + ticket.id(), ticket.safeSummary(), null, null, null,
                ticket.createdAt(), "customer");
    }

    private static String value(Map<String, Object> payload, String key) {
        Object candidate = payload.get(key);
        return candidate == null ? "" : String.valueOf(candidate).trim();
    }

    private static String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(separator));
    }
}
