package com.example.smartpark.tool;

import com.example.smartpark.model.common.Device;
import com.example.smartpark.port.device.DevicePort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceQueryTool {

    private static final String MOCK_NOTICE = "Mock park data only. Tool outputs do not control real park devices.";

    private final DevicePort devicePort;

    public DeviceQueryTool(DevicePort devicePort) {
        this.devicePort = Objects.requireNonNull(devicePort, "devicePort");
    }

    @Tool(name = "lookupDeviceStatus", description = "Look up a park device by deviceId. Returns the device status or an explicit error result. Never invent a device.")
    public DeviceLookupResult lookupDeviceStatus(String deviceId) {
        String normalizedDeviceId = normalize(deviceId);
        if (normalizedDeviceId.isEmpty()) {
            return DeviceLookupResult.error(normalizedDeviceId, "deviceId must not be blank");
        }
        try {
            return DeviceLookupResult.success(normalizedDeviceId, devicePort.getDevice(normalizedDeviceId));
        }
        catch (IllegalArgumentException ex) {
            return DeviceLookupResult.error(normalizedDeviceId, ex.getMessage());
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record DeviceLookupResult(String deviceId, Device device, String error, String notice) {

        public DeviceLookupResult {
            deviceId = normalize(deviceId);
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                deviceId = requireText(deviceId, "deviceId");
                device = Objects.requireNonNull(device, "device");
            }
            else if (device != null) {
                throw new IllegalArgumentException("error results must not include a device");
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
