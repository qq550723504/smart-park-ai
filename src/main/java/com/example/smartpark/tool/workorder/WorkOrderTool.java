package com.example.smartpark.tool.workorder;

import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.port.workorder.WorkOrderPort;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class WorkOrderTool {

    private static final String MOCK_NOTICE = "Mock workflow write only. Creating a work order here does not control real park devices.";

    private final WorkOrderPort workOrderPort;
    private final ReadOnlyWorkOrderTool readOnlyWorkOrderTool;

    public WorkOrderTool(WorkOrderPort workOrderPort) {
        this.workOrderPort = Objects.requireNonNull(workOrderPort, "workOrderPort");
        this.readOnlyWorkOrderTool = new ReadOnlyWorkOrderTool(this);
    }

    @Tool(name = "lookupWorkOrders", description = "Look up work orders by workflowId. Returns zero or more workflow-scoped work orders.")
    public WorkOrderLookupResult lookupWorkOrders(String workflowId) {
        String normalizedWorkflowId = normalize(workflowId);
        if (normalizedWorkflowId.isEmpty()) {
            return new WorkOrderLookupResult(normalizedWorkflowId, List.of(), "workflowId must not be blank", MOCK_NOTICE);
        }
        return new WorkOrderLookupResult(normalizedWorkflowId, workOrderPort.findByWorkflowId(normalizedWorkflowId), null, MOCK_NOTICE);
    }

    @Tool(name = "createWorkOrder", description = "Create a mock work order for the workflow. Preserves workflowId idempotency and does not control real park devices.")
    public WorkOrderCreateResult createWorkOrder(String workflowId, String alertId, String summary) {
        String normalizedWorkflowId = normalize(workflowId);
        String normalizedAlertId = normalize(alertId);
        String normalizedSummary = normalize(summary);
        if (normalizedWorkflowId.isEmpty()) {
            return WorkOrderCreateResult.error(normalizedWorkflowId, normalizedAlertId, "workflowId must not be blank");
        }
        if (normalizedAlertId.isEmpty()) {
            return WorkOrderCreateResult.error(normalizedWorkflowId, normalizedAlertId, "alertId must not be blank");
        }
        if (normalizedSummary.isEmpty()) {
            return WorkOrderCreateResult.error(normalizedWorkflowId, normalizedAlertId, "summary must not be blank");
        }
        try {
            return WorkOrderCreateResult.success(
                    normalizedWorkflowId,
                    normalizedAlertId,
                    workOrderPort.create(normalizedWorkflowId, normalizedAlertId, normalizedSummary));
        }
        catch (IllegalArgumentException ex) {
            return WorkOrderCreateResult.error(normalizedWorkflowId, normalizedAlertId, ex.getMessage());
        }
    }

    public ToolCallback[] diagnosisCallbacks() {
        return ToolCallbacks.from(readOnlyWorkOrderTool);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record WorkOrderLookupResult(String workflowId, List<WorkOrder> workOrders, String error, String notice) {

        public WorkOrderLookupResult {
            workflowId = normalize(workflowId);
            workOrders = List.copyOf(Objects.requireNonNull(workOrders, "workOrders"));
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                workflowId = requireText(workflowId, "workflowId");
            }
        }
    }

    public record WorkOrderCreateResult(String workflowId, String alertId, WorkOrder workOrder, String error, String notice) {

        public WorkOrderCreateResult {
            workflowId = normalize(workflowId);
            alertId = normalize(alertId);
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                workflowId = requireText(workflowId, "workflowId");
                alertId = requireText(alertId, "alertId");
                workOrder = Objects.requireNonNull(workOrder, "workOrder");
            }
            else if (workOrder != null) {
                throw new IllegalArgumentException("error results must not include a work order");
            }
        }

        private static WorkOrderCreateResult success(String workflowId, String alertId, WorkOrder workOrder) {
            return new WorkOrderCreateResult(workflowId, alertId, Objects.requireNonNull(workOrder, "workOrder"), null, MOCK_NOTICE);
        }

        private static WorkOrderCreateResult error(String workflowId, String alertId, String error) {
            return new WorkOrderCreateResult(workflowId, alertId, null, requireText(error, "error"), MOCK_NOTICE);
        }
    }

    private static final class ReadOnlyWorkOrderTool {

        private final WorkOrderTool delegate;

        private ReadOnlyWorkOrderTool(WorkOrderTool delegate) {
            this.delegate = delegate;
        }

        @Tool(name = "lookupWorkOrders", description = "Look up work orders by workflowId. Returns zero or more workflow-scoped work orders.")
        public WorkOrderLookupResult lookupWorkOrders(String workflowId) {
            return delegate.lookupWorkOrders(workflowId);
        }
    }
}
