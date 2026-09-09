package com.example.smartpark.port.workorder;

import com.example.smartpark.model.common.WorkOrder;

import java.util.List;

public interface WorkOrderPort {
    List<WorkOrder> findByWorkflowId(String workflowId);

    WorkOrder create(String workflowId, String alertId, String summary);

    /**
     * Atomically creates the alert action once or returns the work order that already owns it.
     * The workflow id records the first execution that performed the action; alert id is the
     * stable business idempotency key across independently owned workflow executions.
     */
    WorkOrder createOrGetByAlertId(String workflowId, String alertId, String summary);
}
