package com.example.smartpark.collaborationcenter;

import java.time.Instant;
import java.util.Objects;

public record CollaborationSlaSnapshot(
        Instant capturedAt,
        int total,
        int overdue,
        int dueSoon,
        int onTrack,
        int completed,
        int notApplicable) {

    public CollaborationSlaSnapshot {
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        if (total < 0 || overdue < 0 || dueSoon < 0 || onTrack < 0 || completed < 0 || notApplicable < 0) {
            throw new IllegalArgumentException("SLA snapshot counts must not be negative");
        }
        if (overdue + dueSoon + onTrack + completed + notApplicable != total) {
            throw new IllegalArgumentException("SLA snapshot counts must add up to total");
        }
    }
}
