package com.example.smartpark.collaborationcenter;

import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.port.customer.CustomerTicketReader;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CollaborationCenterService {
    private final WorkflowExecutionStore workflows;
    private final CustomerTicketReader tickets;
    private final Clock clock;
    private final CollaborationSlaPolicy slaPolicy;

    public CollaborationCenterService(WorkflowExecutionStore workflows, CustomerTicketPort tickets) {
        this(workflows, tickets::list);
    }

    public CollaborationCenterService(WorkflowExecutionStore workflows, CustomerTicketPort tickets, Clock clock) {
        this(workflows, tickets::list, clock);
    }

    public CollaborationCenterService(WorkflowExecutionStore workflows, CustomerTicketReader tickets) {
        this(workflows, tickets, Clock.systemUTC());
    }

    public CollaborationCenterService(WorkflowExecutionStore workflows, CustomerTicketReader tickets, Clock clock) {
        this.workflows = workflows;
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.slaPolicy = new CollaborationSlaPolicy();
    }

    public List<CollaborationWorkItem> list(WorkItemQuery query) {
        WorkItemQuery requested = Objects.requireNonNull(query, "query");
        List<CollaborationWorkItem> items = new ArrayList<>();
        if (requested.source() == null || requested.source() == CollaborationWorkItem.Source.ALERT_WORKFLOW) {
            if (workflows != null) {
                currentWorkflowSnapshots(workflows.snapshots()).stream().map(this::fromWorkflow)
                        .filter(requested::accepts).forEach(items::add);
            }
        }
        if (requested.source() == null || requested.source() == CollaborationWorkItem.Source.CUSTOMER_TICKET) {
            tickets.listActive().stream().map(this::fromTicket)
                    .filter(requested::accepts).forEach(items::add);
        }
        return items.stream()
                .sorted(comparatorFor(requested.sortMode()))
                .limit(requested.limit())
                .toList();
    }

    private static Comparator<CollaborationWorkItem> comparatorFor(WorkItemQuery.SortMode sortMode) {
        Comparator<CollaborationWorkItem> byUpdatedAt = Comparator.comparing(CollaborationWorkItem::updatedAt)
                .reversed().thenComparing(CollaborationWorkItem::id);
        if (sortMode == WorkItemQuery.SortMode.UPDATED_AT) return byUpdatedAt;
        return Comparator.comparingInt(CollaborationCenterService::slaRank).thenComparing(byUpdatedAt);
    }

    private static int slaRank(CollaborationWorkItem item) {
        return switch (item.slaState()) {
            case OVERDUE -> 0;
            case DUE_SOON -> 1;
            case ON_TRACK -> 2;
            case NOT_APPLICABLE -> 3;
            case COMPLETED -> 4;
        };
    }

    private CollaborationWorkItem fromWorkflow(WorkflowSnapshot snapshot) {
        Map<String, Object> payload = snapshot.statePayload();
        String parkId = firstNonBlank(value(payload, "parkId"), nestedValue(payload, "alert", "parkId"),
                nestedValue(payload, "parkContext", "parkId"));
        String buildingId = firstNonBlank(value(payload, "buildingId"), nestedValue(payload, "alert", "buildingId"),
                nestedValue(payload, "parkContext", "buildingId"));
        String deviceId = firstNonBlank(value(payload, "deviceId"), nestedValue(payload, "alert", "deviceId"),
                nestedValue(payload, "parkContext", "deviceId"), nestedValue(payload, "parkContext", "device", "id"));
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
        Instant updatedAt = workflowUpdatedAt(snapshot, payload, diagnosis);
        CollaborationWorkItem.Status status = CollaborationWorkItem.Status.valueOf(snapshot.status().name());
        CollaborationWorkItem.Priority priority = priorityFor(snapshot, payload, diagnosis);
        Instant openedAt = workflowOpenedAt(snapshot, payload);
        CollaborationSlaPolicy.SlaEvaluation sla = slaPolicy.evaluate(
                CollaborationWorkItem.Source.ALERT_WORKFLOW, priority, status,
                openedAt, clock.instant());
        return new CollaborationWorkItem(
                "ALERT_WORKFLOW:" + snapshot.workflowId(),
                CollaborationWorkItem.Source.ALERT_WORKFLOW,
                status, priority, "告警处置 " + snapshot.alertId(), summary, parkId, buildingId, deviceId, updatedAt,
                openedAt, sla.dueAt(), sla.state(), "workflow");
    }

    private CollaborationWorkItem fromTicket(CustomerTicket ticket) {
        CollaborationWorkItem.Status status = CollaborationWorkItem.Status.valueOf(CustomerTicketStatus.valueOf(ticket.status()).name());
        CollaborationSlaPolicy.SlaEvaluation sla = slaPolicy.evaluate(
                CollaborationWorkItem.Source.CUSTOMER_TICKET, CollaborationWorkItem.Priority.NORMAL, status,
                ticket.createdAt(), clock.instant());
        return new CollaborationWorkItem(
                "CUSTOMER_TICKET:" + ticket.id(),
                CollaborationWorkItem.Source.CUSTOMER_TICKET,
                status,
                CollaborationWorkItem.Priority.NORMAL,
                "客服工单 " + ticket.id(), ticket.safeSummary(), null, null, null,
                ticket.updatedAt(), ticket.createdAt(), sla.dueAt(), sla.state(), "customer");
    }

    private static CollaborationWorkItem.Priority priorityFor(WorkflowSnapshot snapshot, Map<String, Object> payload,
                                                               Diagnosis diagnosis) {
        return hasHighRiskSignal(snapshot, payload, diagnosis)
                ? CollaborationWorkItem.Priority.HIGH : CollaborationWorkItem.Priority.NORMAL;
    }

    private static Instant workflowOpenedAt(WorkflowSnapshot snapshot, Map<String, Object> payload) {
        Instant occurredAt = instantValue(payload, "alert", "occurredAt");
        if (occurredAt != null) return occurredAt;
        Instant createdAt = instantValue(payload, "createdAt");
        if (createdAt != null) return createdAt;
        Instant updatedAt = instantValue(payload, "updatedAt");
        return updatedAt != null ? updatedAt : workflowUpdatedAt(snapshot);
    }

    private static String value(Map<String, Object> payload, String key) {
        Object candidate = payload.get(key);
        return candidate == null ? "" : String.valueOf(candidate).trim();
    }

    private static List<WorkflowSnapshot> currentWorkflowSnapshots(List<WorkflowSnapshot> snapshots) {
        Map<String, WorkflowSnapshot> currentByAlert = new java.util.LinkedHashMap<>();
        for (WorkflowSnapshot snapshot : snapshots) {
            WorkflowSnapshot current = currentByAlert.get(snapshot.alertId());
            if (current == null || isPreferredWorkflow(snapshot, current)) {
                currentByAlert.put(snapshot.alertId(), snapshot);
            }
        }
        return List.copyOf(currentByAlert.values());
    }

    private static boolean isPreferredWorkflow(WorkflowSnapshot candidate, WorkflowSnapshot current) {
        boolean candidateRetryable = isRetryable(candidate.status());
        boolean currentRetryable = isRetryable(current.status());
        if (candidateRetryable != currentRetryable) return !candidateRetryable;
        int byUpdatedAt = workflowUpdatedAt(candidate).compareTo(workflowUpdatedAt(current));
        if (byUpdatedAt != 0) return byUpdatedAt > 0;
        int bySequence = Long.compare(candidate.eventSequence(), current.eventSequence());
        if (bySequence != 0) return bySequence > 0;
        return candidate.workflowId().compareTo(current.workflowId()) > 0;
    }

    private static boolean isRetryable(WorkflowStatus status) {
        return status == WorkflowStatus.FAILED || status == WorkflowStatus.WORK_ORDER_FAILED;
    }

    private static boolean hasHighRiskSignal(
            WorkflowSnapshot snapshot, Map<String, Object> payload, Diagnosis diagnosis) {
        return diagnosis != null && diagnosis.riskLevel() == RiskLevel.HIGH
                || snapshot.workOrder() != null && snapshot.workOrder().riskLevel() == RiskLevel.HIGH
                || isHighRisk(value(payload, "riskLevel"))
                || isHighRisk(nestedValue(payload, "classification", "riskLevel"))
                || isHighRisk(nestedValue(payload, "alert", "riskHint"))
                || isHighRisk(nestedValue(payload, "diagnosis", "riskLevel"));
    }

    private static boolean isHighRisk(String value) {
        return "HIGH".equalsIgnoreCase(value);
    }

    private static Instant workflowUpdatedAt(
            WorkflowSnapshot snapshot, Map<String, Object> payload, Diagnosis diagnosis) {
        Instant stateUpdatedAt = instantValue(payload, "updatedAt");
        if (stateUpdatedAt != null) return stateUpdatedAt;
        if (snapshot.workOrder() != null) return snapshot.workOrder().updatedAt();
        if (snapshot.approval().isPresent()) return snapshot.approval().orElseThrow().decidedAt();
        if (diagnosis != null) return diagnosis.diagnosedAt();
        Instant occurredAt = instantValue(payload, "alert", "occurredAt");
        return occurredAt == null ? Instant.EPOCH : occurredAt;
    }

    private static Instant workflowUpdatedAt(WorkflowSnapshot snapshot) {
        return workflowUpdatedAt(snapshot, snapshot.statePayload(), snapshot.diagnosis());
    }

    private static Instant instantValue(Map<String, Object> payload, String... path) {
        String value = path.length == 1 ? value(payload, path[0]) : nestedValue(payload, path);
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String nestedValue(Map<String, Object> payload, String... path) {
        Object current = payload;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(key);
        }
        return current == null ? "" : String.valueOf(current).trim();
    }

    private static String firstNonBlank(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(separator));
    }
}
