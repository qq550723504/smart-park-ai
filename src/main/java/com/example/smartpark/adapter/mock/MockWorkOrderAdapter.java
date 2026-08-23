package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.port.workorder.WorkOrderPort;

import java.util.List;

public final class MockWorkOrderAdapter implements WorkOrderPort {
    private final MockParkDataStore dataStore;

    public MockWorkOrderAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public List<WorkOrder> findByWorkflowId(String workflowId) {
        return dataStore.findByWorkflowId(workflowId);
    }

    @Override
    public WorkOrder create(String workflowId, String alertId, String summary) {
        return dataStore.buildWorkOrder(workflowId, alertId, summary);
    }
}
