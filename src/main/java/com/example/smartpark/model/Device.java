package com.example.smartpark.model;

import java.time.Instant;
import java.util.Objects;

public record Device(
        String id,
        String parkId,
        String buildingId,
        String name,
        String category,
        String status,
        Instant installedAt) {

    public Device {
        id = Objects.requireNonNull(id, "id");
        parkId = Objects.requireNonNull(parkId, "parkId");
        buildingId = Objects.requireNonNull(buildingId, "buildingId");
        name = Objects.requireNonNull(name, "name");
        category = Objects.requireNonNull(category, "category");
        status = Objects.requireNonNull(status, "status");
        installedAt = Objects.requireNonNull(installedAt, "installedAt");
    }
}
