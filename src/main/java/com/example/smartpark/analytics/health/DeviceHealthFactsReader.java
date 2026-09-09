package com.example.smartpark.analytics.health;

import java.time.Instant;
import java.util.List;

public interface DeviceHealthFactsReader {
    Facts read(String deviceId);

    default String findDeviceIdByAlertId(String alertId) { return null; }

    record DeviceFact(String deviceId, String buildingId, String deviceType, String status, Instant snapshotAt) {}
    record AlertFact(String alertId, String buildingId, String deviceId, String category,
                     String riskLevel, String status, Instant occurredAt) {}
    record Facts(DeviceFact device, List<AlertFact> activeAlerts, boolean available,
                 boolean truncated, String failureCode) {
        public Facts { activeAlerts = List.copyOf(activeAlerts == null ? List.of() : activeAlerts); }
        public static Facts available(DeviceFact device, List<AlertFact> alerts) {
            return available(device, alerts, false);
        }
        public static Facts available(DeviceFact device, List<AlertFact> alerts, boolean truncated) {
            return new Facts(device, alerts, true, truncated, null);
        }
        public static Facts unavailable(String code) { return new Facts(null, List.of(), false, false, code); }
    }
}
