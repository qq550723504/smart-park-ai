package com.example.smartpark.showcase;

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
        Instant lastVerifiedAt,
        ShowcaseLaunchInput launchInput) {

    private static final String UNVERIFIED_REASON = "本次部署尚未完成在线验证";

    public ShowcaseScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        requiredCapabilities = List.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities"));
        proofTypes = List.copyOf(Objects.requireNonNull(proofTypes, "proofTypes"));
        launchInput = Objects.requireNonNull(launchInput, "launchInput");
        if (!launchInput.equals(ShowcaseLaunchInput.forScenario(id))) {
            throw new IllegalArgumentException("launchInput must match the server-owned guided scenario input");
        }

        if (live != (status == ShowcaseScenarioStatus.READY)) {
            throw new IllegalArgumentException("live must be true exactly when status is READY");
        }
        if (status == ShowcaseScenarioStatus.READY) {
            if (unavailableReason != null || lastVerifiedAt == null) {
                throw new IllegalArgumentException(
                        "READY requires a verification receipt and no unavailable reason");
            }
        } else if (!isAllowedUnavailableReason(id, status, unavailableReason)
                || lastVerifiedAt != null) {
            throw new IllegalArgumentException(
                    "non-ready scenarios require a fixed public reason and no verification receipt");
        }
    }

    public ShowcaseScenario(
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
        this(id, status, live, title, businessQuestion, expectedDurationSeconds,
                requiredCapabilities, proofTypes, humanBoundary, unavailableReason,
                lastVerifiedAt, ShowcaseLaunchInput.forScenario(id));
    }

    private static boolean isAllowedUnavailableReason(
            ShowcaseScenarioId id, ShowcaseScenarioStatus status, String reason) {
        return switch (status) {
            case NOT_READY -> UNVERIFIED_REASON.equals(reason);
            case DISABLED -> disabledReason(id).equals(reason);
            case READY -> false;
        };
    }

    private static String disabledReason(ShowcaseScenarioId id) {
        return switch (id) {
            case ALERT_WORKFLOW -> "本次部署未启用告警处置";
            case EXPERT_COLLABORATION -> "本次部署未启用专家协作";
            case OPERATIONS_ANALYSIS -> "本次部署未启用运营分析";
            case VOICE_ASSISTANT -> "本次部署未启用语音体验";
            case CUSTOMER_SERVICE -> "本次部署未启用园区客服";
        };
    }
}
