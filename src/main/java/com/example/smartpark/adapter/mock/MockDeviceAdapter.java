package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.Device;
import com.example.smartpark.port.device.DevicePort;

public final class MockDeviceAdapter implements DevicePort {
    private final MockParkDataStore dataStore;

    public MockDeviceAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public Device getDevice(String deviceId) {
        return dataStore.getDevice(deviceId);
    }
}
