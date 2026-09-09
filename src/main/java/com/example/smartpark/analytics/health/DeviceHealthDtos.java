package com.example.smartpark.analytics.health;

import java.time.Instant;
import java.util.List;

public final class DeviceHealthDtos {
    private DeviceHealthDtos() {}

    public enum HealthStatus { HEALTHY, ATTENTION, DEGRADED, CRITICAL, UNKNOWN }
    public enum Availability { AVAILABLE, PARTIAL, UNAVAILABLE }

    public record Evidence(String type, String reference, Instant occurredAt, String summary) {}
    public record Source(String system, Availability status) {}

    public record Response(String deviceId, String buildingId, String deviceType,
                           HealthStatus healthStatus, Availability availability,
                           List<String> reasons, List<Evidence> evidence,
                           List<Source> sources, Instant asOf) {
        public Response {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            sources = List.copyOf(sources == null ? List.of() : sources);
        }
    }
}
