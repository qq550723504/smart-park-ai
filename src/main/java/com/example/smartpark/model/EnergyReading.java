package com.example.smartpark.model;

import java.time.Instant;
import java.util.Objects;

public record EnergyReading(
        String meterId,
        String parkId,
        String buildingId,
        Instant measuredAt,
        double currentKwh,
        double baselineKwh,
        double peakDemandKw) {

    public EnergyReading {
        meterId = requireText(meterId, "meterId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        measuredAt = Objects.requireNonNull(measuredAt, "measuredAt");
        requireNonNegative(currentKwh, "currentKwh");
        if (!Double.isFinite(baselineKwh) || baselineKwh <= 0.0) {
            throw new IllegalArgumentException("baselineKwh must be positive and finite");
        }
        requireNonNegative(peakDemandKw, "peakDemandKw");
    }

    public double varianceKwh() {
        return currentKwh - baselineKwh;
    }

    public double varianceRatio() {
        return varianceKwh() / baselineKwh;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static void requireNonNegative(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative and finite");
        }
    }
}
