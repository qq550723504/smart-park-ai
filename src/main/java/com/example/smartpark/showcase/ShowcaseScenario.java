package com.example.smartpark.showcase;

import com.example.smartpark.model.common.PublicMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ShowcaseScenario(
        ShowcaseScenarioId id,
        ShowcaseScenarioStatus status,
        boolean live,
        String title,
        String businessQuestion,
        int expectedDurationSeconds,
        List<String> requiredCapabilities,
        List<String> proofTypes,
        String humanBoundary,
        String unavailableReason,
        Instant lastVerifiedAt) {

    public ShowcaseScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        requiredCapabilities = List.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities"));
        proofTypes = List.copyOf(Objects.requireNonNull(proofTypes, "proofTypes"));

        if (live != (status == ShowcaseScenarioStatus.READY)) {
            throw new IllegalArgumentException("live must be true exactly when status is READY");
        }
        if (status == ShowcaseScenarioStatus.READY) {
            if (unavailableReason != null || lastVerifiedAt == null) {
                throw new IllegalArgumentException(
                        "READY requires a verification receipt and no unavailable reason");
            }
        } else if (!PublicMetadata.isSafePublicText(unavailableReason) || lastVerifiedAt != null) {
            throw new IllegalArgumentException(
                    "non-ready scenarios require a safe reason and no verification receipt");
        }
    }
}
