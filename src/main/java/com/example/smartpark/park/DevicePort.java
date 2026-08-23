package com.example.smartpark.park;

import com.example.smartpark.model.Device;

public interface DevicePort {
    Device getDevice(String deviceId);
}
