package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.port.alert.AlertPort;

import java.util.List;

public final class MockAlertAdapter implements AlertPort {
    private final MockParkDataStore dataStore;

    public MockAlertAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public Alert getAlert(String alertId) {
        return dataStore.getAlert(alertId);
    }

    @Override
    public List<Alert> listActive() {
        return dataStore.listAlerts();
    }

    @Override
    public List<Alert> findHistory(String deviceId) {
        return dataStore.findHistory(deviceId);
    }
}
