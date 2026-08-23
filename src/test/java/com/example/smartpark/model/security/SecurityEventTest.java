package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventTest {

    @Test
    void rejectsBlankBoundaryText() {
        Stream.of("eventId", "parkId", "buildingId", "eventType", "evidenceSummary")
                .forEach(field -> assertThatThrownBy(() -> newEventWithBlank(field))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(field));
    }

    private SecurityEvent newEventWithBlank(String field) {
        String eventId = field.equals("eventId") ? " " : "SEC-001";
        String parkId = field.equals("parkId") ? "\t" : "PARK-A";
        String buildingId = field.equals("buildingId") ? "  " : "A1";
        String eventType = field.equals("eventType") ? "" : "UNAUTHORIZED_ACCESS";
        String evidenceSummary = field.equals("evidenceSummary") ? "\n" : "已脱敏：门禁异常，未保存原始影像";
        return new SecurityEvent(
                eventId,
                parkId,
                buildingId,
                eventType,
                Instant.parse("2026-08-23T01:00:00Z"),
                evidenceSummary);
    }
}
