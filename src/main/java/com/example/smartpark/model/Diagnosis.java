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
        diagnosedAt = Objects.requireNonNull(diagnosedAt, "diagnosedAt");
    }
}
