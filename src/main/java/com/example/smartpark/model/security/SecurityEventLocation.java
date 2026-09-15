package com.example.smartpark.model.security;

/**
 * Optional finer-grained location for a security event. {@code parkId} and
 * {@code buildingId} stay on the event for correlation; this carries zone,
 * device and camera scope when the source provides it.
 */
public record SecurityEventLocation(String zoneId, String deviceId, String cameraId) {
    public SecurityEventLocation {
        zoneId = SecurityIdentifierPolicy.optionalSafe(zoneId, "zoneId");
        deviceId = SecurityIdentifierPolicy.optionalSafe(deviceId, "deviceId");
        cameraId = SecurityIdentifierPolicy.optionalSafe(cameraId, "cameraId");
    }

    public static SecurityEventLocation empty() {
        return new SecurityEventLocation(null, null, null);
    }
}
