package com.example.smartpark.port.workorder;

import com.example.smartpark.model.common.WorkOrder;

import java.util.List;

public interface WorkOrderPort {
    List<WorkOrder> findByWorkflowId(String workflowId);

    WorkOrder create(String workflowId, String alertId, String summary);
}
