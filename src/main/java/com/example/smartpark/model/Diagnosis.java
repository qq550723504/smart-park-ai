package com.example.smartpark.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Diagnosis(
        String id,
        String alertId,
        String deviceId,
        RiskLevel riskLevel,
        String rootCause,
        String summary,
        List<String> evidence,
        String recommendedAction,
        double confidence,
        Instant diagnosedAt) {

    public Diagnosis {
        id = Objects.requireNonNull(id, "id");
        alertId = Objects.requireNonNull(alertId, "alertId");
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        rootCause = Objects.requireNonNull(rootCause, "rootCause");
        summary = Objects.requireNonNull(summary, "summary");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        recommendedAction = Objects.requireNonNull(recommendedAction, "recommendedAction");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        diagnosedAt = Objects.requireNonNull(diagnosedAt, "diagnosedAt");
    }

    /**
     * Compatibility constructor for diagnoses restored from pre-confidence checkpoints.
     * A missing confidence fails closed at zero and therefore always requires approval.
     */
    public Diagnosis(
            String id,
            String alertId,
            String deviceId,
            RiskLevel riskLevel,
            String rootCause,
            String summary,
            List<String> evidence,
            String recommendedAction,
            Instant diagnosedAt) {
        this(
                id,
                alertId,
                deviceId,
                riskLevel,
                rootCause,
                summary,
                evidence,
                recommendedAction,
                0.0,
                diagnosedAt);
    }
}
