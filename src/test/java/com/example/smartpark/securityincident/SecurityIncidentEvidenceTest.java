package com.example.smartpark.securityincident;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityIncidentEvidenceTest {

    @Test
    void rejectsRawPayloadMarkers() {
        Stream.of(
                        "REDACTED: data:image/png;base64,AAAA",
                        "REDACTED: raw image bytes",
                        "REDACTED: face embedding bytes")
                .forEach(summary -> assertThatThrownBy(() -> evidence(summary))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("summary"));
    }

    @Test
    void rejectsEmptyOrOversizedSummaries() {
        Stream.of("REDACTED:", "REDACTED: " + "x".repeat(512))
                .forEach(summary -> assertThatThrownBy(() -> evidence(summary))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("summary"));
    }

    private static SecurityIncidentEvidence evidence(String summary) {
        return new SecurityIncidentEvidence("SEC-1", Instant.parse("2026-09-02T08:00:00Z"), summary);
    }
}
