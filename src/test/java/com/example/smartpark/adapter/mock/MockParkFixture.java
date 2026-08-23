package com.example.smartpark.adapter.mock;

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

public final class MockParkFixture implements DevicePort, EnergyPort, AlertPort, WorkOrderPort, KnowledgePort {
    private final MockParkDataStore dataStore = new MockParkDataStore();
    private final MockAlertAdapter alerts = new MockAlertAdapter(dataStore);
    private final MockDeviceAdapter devices = new MockDeviceAdapter(dataStore);
    private final MockEnergyAdapter energy = new MockEnergyAdapter(dataStore);
    private final MockKnowledgeAdapter knowledge = new MockKnowledgeAdapter(dataStore);
    private final MockWorkOrderAdapter workOrders = new MockWorkOrderAdapter(dataStore);

    public MockAlertAdapter alerts() {
        return alerts;
    }

    public MockDeviceAdapter devices() {
        return devices;
    }

    public MockEnergyAdapter energy() {
        return energy;
    }

    public MockKnowledgeAdapter knowledge() {
        return knowledge;
    }

    public MockWorkOrderAdapter workOrders() {
        return workOrders;
    }

    public void reset() {
        dataStore.reset();
    }

    @Override
    public Device getDevice(String deviceId) {
        return devices.getDevice(deviceId);
    }

    @Override
    public EnergyReading getLatestEnergyReading(String meterId) {
        return energy.getLatestEnergyReading(meterId);
    }

    @Override
    public Alert getAlert(String alertId) {
        return alerts.getAlert(alertId);
    }

    @Override
    public List<Alert> findHistory(String deviceId) {
        return alerts.findHistory(deviceId);
    }

    @Override
    public List<WorkOrder> findByWorkflowId(String workflowId) {
        return workOrders.findByWorkflowId(workflowId);
    }

    @Override
    public WorkOrder create(String workflowId, String alertId, String summary) {
        return workOrders.create(workflowId, alertId, summary);
    }

    @Override
    public List<KnowledgeDocument> search(String query) {
        return knowledge.search(query);
    }
}
