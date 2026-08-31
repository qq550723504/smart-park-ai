package com.example.smartpark.showcase;

import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.port.workorder.WorkOrderPort;

import java.util.List;

public final class RejectingPreflightWorkOrderPort implements WorkOrderPort {

    @Override
    public List<WorkOrder> findByWorkflowId(String workflowId) {
        return List.of();
    }

    @Override
    public WorkOrder create(String workflowId, String alertId, String summary) {
        throw new IllegalStateException("preflight work-order writes are forbidden");
    }
}
