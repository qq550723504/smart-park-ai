package com.example.smartpark.analytics.anomaly;

import java.time.Duration;
import java.time.Instant;

/** Immutable, bounded filters shared by the anomaly overview and evidence reads. */
public record OperationsAnomalyQuery(
        Instant from,
        Instant to,
        String buildingId,
        String riskLevel,
        String category,
        String status,
        String deviceType) {

    public OperationsAnomalyQuery normalized(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        Instant normalizedTo = to == null ? now : to;
        Instant normalizedFrom = from == null ? normalizedTo.minus(Duration.ofDays(7)) : from;
        return new OperationsAnomalyQuery(
                normalizedFrom,
                normalizedTo,
                normalizeFilter(buildingId),
                normalizeFilter(riskLevel),
                normalizeFilter(category),
                normalizeFilter(status),
                normalizeFilter(deviceType));
    }

    public void validate(Duration maxWindow) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must be provided before validation");
        }
        if (maxWindow == null || maxWindow.isZero() || maxWindow.isNegative()) {
            throw new IllegalArgumentException("maxWindow must be positive");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).compareTo(maxWindow) > 0) {
            throw new IllegalArgumentException("anomaly query window is too large");
        }
    }

    private static String normalizeFilter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
