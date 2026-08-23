package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventTest {

    @Test
    void rejectsBlankBoundaryText() {
        Stream.of("eventId", "parkId", "buildingId", "eventType", "evidenceSummary")
                .forEach(field -> assertThatThrownBy(() -> newEventWithBlank(field))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(field));
    }

    @Test
    void acceptsAndTrimsRedactedEvidenceSummary() {
        SecurityEvent event = newEvent("  REDACTED: 门禁异常摘要  ");

        assertThat(event.evidenceSummary()).isEqualTo("REDACTED: 门禁异常摘要");
    }

    @Test
    void rejectsEvidenceSummaryWithoutStableRedactedPrefix() {
        assertThatThrownBy(() -> newEvent("门禁异常摘要"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceSummary");
    }

    @Test
    void rejectsEvidenceSummaryContainingRawPayloadMarkers() {
        Stream.of(
                        "REDACTED: data:image/png;base64,AAAA",
                        "REDACTED: BASE64 encoded payload",
                        "REDACTED: raw video bytes",
                        "REDACTED: raw image bytes",
                        "REDACTED: 原始视频",
                        "REDACTED: 原始图片",
                        "REDACTED: face embedding bytes")
                .forEach(evidenceSummary -> assertThatThrownBy(() -> newEvent(evidenceSummary))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("evidenceSummary"));
    }

    @Test
    void acceptsBusinessTermsWhenTheSummaryStatesThatOriginalDataWasNotRetained() {
        Stream.of(
                        "REDACTED: 人脸识别失败，未保留原始数据",
                        "REDACTED: 身份证件已脱敏")
                .forEach(evidenceSummary -> assertThat(newEvent(evidenceSummary).evidenceSummary())
                        .isEqualTo(evidenceSummary));
    }

    @Test
    void rejectsEvidenceSummaryAboveReasonableLengthLimit() {
        assertThatThrownBy(() -> newEvent("REDACTED: " + "x".repeat(512)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceSummary");
    }

    private SecurityEvent newEventWithBlank(String field) {
        String eventId = field.equals("eventId") ? " " : "SEC-001";
        String parkId = field.equals("parkId") ? "\t" : "PARK-A";
        String buildingId = field.equals("buildingId") ? "  " : "A1";
        String eventType = field.equals("eventType") ? "" : "UNAUTHORIZED_ACCESS";
        String evidenceSummary = field.equals("evidenceSummary") ? "\n" : "REDACTED: 门禁异常摘要";
        return newEvent(eventId, parkId, buildingId, eventType, evidenceSummary);
    }

    private SecurityEvent newEvent(String evidenceSummary) {
        return newEvent("SEC-001", "PARK-A", "A1", "UNAUTHORIZED_ACCESS", evidenceSummary);
    }

    private SecurityEvent newEvent(
            String eventId,
            String parkId,
            String buildingId,
            String eventType,
            String evidenceSummary) {
        return new SecurityEvent(
                eventId,
                parkId,
                buildingId,
                eventType,
                Instant.parse("2026-08-23T01:00:00Z"),
                evidenceSummary);
    }
}
