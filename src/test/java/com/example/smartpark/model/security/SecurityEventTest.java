package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventTest {

    @Test
    void rejectsBlankBoundaryText() {
        Stream.of("eventId", "parkId", "buildingId", "rawEventType", "evidenceSummary")
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

    @Test
    void mapsLegacyRawEventTypeToStandardTypeAndPreservesRawValue() {
        SecurityEvent event = newEvent("REDACTED: 门禁异常摘要");

        assertThat(event.eventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
        assertThat(event.rawEventType()).isEqualTo("UNAUTHORIZED_ACCESS");
        assertThat(event.observedAt()).isEqualTo(event.occurredAt());
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-23T01:00:00Z"));
    }

    @Test
    void fallsBackToUnknownStandardTypeForUnmappedRawValue() {
        SecurityEvent event = newEvent("SEC-002", "PARK-A", "A1", "VENDOR_PRIVATE_CODE_42", "REDACTED: 摘要");

        assertThat(event.eventType()).isEqualTo(SecurityEventType.UNKNOWN);
        assertThat(event.rawEventType()).isEqualTo("VENDOR_PRIVATE_CODE_42");
    }

    @Test
    void appliesSafeDefaultsForSeverityConfidencePrivacyAndDisposition() {
        SecurityEvent event = newEvent("REDACTED: 门禁异常摘要");

        assertThat(event.severity()).isEqualTo(SecurityEventSeverity.UNKNOWN);
        assertThat(event.confidence()).isNull();
        assertThat(event.privacy()).isEqualTo(SecurityPrivacyMetadata.redactedOnly());
        assertThat(event.disposition()).isEqualTo(SecurityDispositionRecord.unreviewed());
        assertThat(event.receivedAt()).isEqualTo(event.observedAt());
    }

    @Test
    void rejectsConfidenceOutsideUnitInterval() {
        Stream.of(-0.1d, 1.1d, Double.NaN).forEach(confidence ->
                assertThatThrownBy(() -> structuredEvent(SecurityEventType.FIRE_SMOKE, confidence,
                        SecuritySourceRef.unknown(), SecurityPrivacyMetadata.redactedOnly()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("confidence"));
    }

    @Test
    void acceptsConfidenceWithinUnitInterval() {
        SecurityEvent event = structuredEvent(SecurityEventType.FIRE_SMOKE, 0.87d,
                SecuritySourceRef.unknown(), SecurityPrivacyMetadata.redactedOnly());

        assertThat(event.confidence()).isEqualTo(0.87d);
    }

    @Test
    void rejectsSourceIdContainingCredentialsOrUrls() {
        Stream.of("rtsp://user:pass@cam-1", "https://internal.example/cam", "token=abc", "password:secret")
                .forEach(sourceId -> assertThatThrownBy(() -> new SecuritySourceRef(
                        SecuritySourceType.CAMERA_ANALYTICS, sourceId))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("sourceId"));
    }

    @Test
    void rejectsPrivacyMetadataClaimingPersonalDataOrStoredMedia() {
        assertThatThrownBy(() -> new SecurityPrivacyMetadata(
                SecurityPrivacyMetadata.RedactionPolicy.REDACTED_ONLY, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("personalDataPresent");
        assertThatThrownBy(() -> new SecurityPrivacyMetadata(
                SecurityPrivacyMetadata.RedactionPolicy.REDACTED_ONLY, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mediaStored");
    }

    private SecurityEvent structuredEvent(SecurityEventType eventType, Double confidence,
                                          SecuritySourceRef source, SecurityPrivacyMetadata privacy) {
        Instant observedAt = Instant.parse("2026-08-23T01:00:00Z");
        return new SecurityEvent("SEC-STRUCTURED", "PARK-A", "A1", eventType, "RAW_CODE",
                source, SecurityEventLocation.empty(), observedAt, observedAt,
                SecurityEventSeverity.UNKNOWN, confidence, privacy,
                SecurityDispositionRecord.unreviewed(), "test-adapter", "1", "REDACTED: 摘要");
    }

    private SecurityEvent newEventWithBlank(String field) {
        String eventId = field.equals("eventId") ? " " : "SEC-001";
        String parkId = field.equals("parkId") ? "\t" : "PARK-A";
        String buildingId = field.equals("buildingId") ? "  " : "A1";
        String rawEventType = field.equals("rawEventType") ? "" : "UNAUTHORIZED_ACCESS";
        String evidenceSummary = field.equals("evidenceSummary") ? "\n" : "REDACTED: 门禁异常摘要";
        return newEvent(eventId, parkId, buildingId, rawEventType, evidenceSummary);
    }

    @Test
    void replacesOnlyTheDispositionWhenCopyingAnEvent() {
        SecurityEvent event = newEvent("REDACTED: 门禁异常摘要");
        SecurityDispositionRecord decision = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                Instant.parse("2026-08-23T02:00:00Z"));

        SecurityEvent copy = event.withDisposition(decision);

        assertThat(copy.disposition()).isSameAs(decision);
        assertThat(copy).usingRecursiveComparison().ignoringFields("disposition").isEqualTo(event);
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
