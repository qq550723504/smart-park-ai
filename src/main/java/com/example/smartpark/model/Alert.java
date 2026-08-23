package com.example.smartpark.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Alert(
        String id,
        String parkId,
        String buildingId,
        String deviceId,
        AlertClassification classification,
        RiskLevel riskHint,
        String summary,
        Instant occurredAt,
        List<String> evidence) {

    public Alert {
        id = Objects.requireNonNull(id, "id");
        parkId = Objects.requireNonNull(parkId, "parkId");
        buildingId = Objects.requireNonNull(buildingId, "buildingId");
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        classification = Objects.requireNonNull(classification, "classification");
        riskHint = Objects.requireNonNull(riskHint, "riskHint");
        summary = Objects.requireNonNull(summary, "summary");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
