package com.example.smartpark.port.device;

import com.example.smartpark.model.common.Device;

public interface DevicePort {
    Device getDevice(String deviceId);
}
