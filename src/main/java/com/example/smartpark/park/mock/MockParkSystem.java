package com.example.smartpark.park.mock;

import com.example.smartpark.adapter.mock.MockParkDataStore;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.Device;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.workorder.WorkOrderPort;

import java.util.List;

public class MockParkSystem implements DevicePort, EnergyPort, AlertPort, WorkOrderPort, KnowledgePort {
    private final MockParkDataStore dataStore;

    public MockParkSystem() { dataStore = new MockParkDataStore(); }
    public final void reset() { dataStore.reset(); }

    @Override public Device getDevice(String deviceId) { return dataStore.getDevice(deviceId); }
    @Override public EnergyReading getLatestEnergyReading(String meterId) { return dataStore.getLatestEnergyReading(meterId); }
    @Override public Alert getAlert(String alertId) { return dataStore.getAlert(alertId); }
    @Override public List<Alert> findHistory(String deviceId) { return dataStore.findHistory(deviceId); }
    @Override public List<WorkOrder> findByWorkflowId(String workflowId) { return dataStore.findByWorkflowId(workflowId); }
    @Override public WorkOrder create(String workflowId, String alertId, String summary) { return dataStore.buildWorkOrder(workflowId, alertId, summary); }
    @Override public List<KnowledgeDocument> search(String query) { return dataStore.search(query); }
}
