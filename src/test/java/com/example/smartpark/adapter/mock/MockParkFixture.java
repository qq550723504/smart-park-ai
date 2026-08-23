package com.example.smartpark.adapter.mock;

public final class MockParkFixture {
    private final MockParkDataStore dataStore = new MockParkDataStore();
    private final MockAlertAdapter alerts = new MockAlertAdapter(dataStore);
    private final MockDeviceAdapter devices = new MockDeviceAdapter(dataStore);
    private final MockEnergyAdapter energy = new MockEnergyAdapter(dataStore);
    private final MockSecurityAdapter security = new MockSecurityAdapter(dataStore);
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

    public MockSecurityAdapter security() {
        return security;
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

}
