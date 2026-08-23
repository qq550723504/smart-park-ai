package com.example.smartpark.park;

import com.example.smartpark.model.WorkOrder;

import java.util.List;

public interface WorkOrderPort {
    List<WorkOrder> findByWorkflowId(String workflowId);

    WorkOrder create(String workflowId, String alertId, String summary);
}
