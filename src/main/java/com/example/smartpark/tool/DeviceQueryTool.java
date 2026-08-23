package com.example.smartpark.tool;

import com.example.smartpark.model.Device;
import com.example.smartpark.park.DevicePort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class DeviceQueryTool {

    private static final String MOCK_NOTICE = "Mock park data only. Tool outputs do not control real park devices.";

    private final DevicePort devicePort;

    public DeviceQueryTool(DevicePort devicePort) {
        this.devicePort = Objects.requireNonNull(devicePort, "devicePort");
    }

    @Tool(name = "lookupDeviceStatus", description = "Look up a park device by deviceId. Returns the device status or an explicit error result. Never invent a device.")
    public DeviceLookupResult lookupDeviceStatus(String deviceId) {
        String normalizedDeviceId = requireText(deviceId, "deviceId");
        try {
            return DeviceLookupResult.success(normalizedDeviceId, devicePort.getDevice(normalizedDeviceId));
        }
        catch (IllegalArgumentException ex) {
            return DeviceLookupResult.error(normalizedDeviceId, ex.getMessage());
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record DeviceLookupResult(String deviceId, Device device, String error, String notice) {

        public DeviceLookupResult {
            deviceId = requireText(deviceId, "deviceId");
            notice = requireText(notice, "notice");
            if ((device == null) == (error == null || error.isBlank())) {
                throw new IllegalArgumentException("exactly one of device or error must be present");
            }
            if (error != null) {
                error = error.trim();
            }
        }

        private static DeviceLookupResult success(String deviceId, Device device) {
            return new DeviceLookupResult(deviceId, Objects.requireNonNull(device, "device"), null, MOCK_NOTICE);
        }

        private static DeviceLookupResult error(String deviceId, String error) {
            return new DeviceLookupResult(deviceId, null, requireText(error, "error"), MOCK_NOTICE);
        }
    }
}
