package com.example.smartpark.analytics.anomaly;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsAnomalyQueryTest {

    @Test
    void rejectsReversedAndOverlongWindows() {
        OperationsAnomalyQuery reversed = new OperationsAnomalyQuery(
                Instant.parse("2026-09-03T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"), null, null, null, null, null);
        OperationsAnomalyQuery overlong = new OperationsAnomalyQuery(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-01T00:00:00Z"), null, null, null, null, null);

        assertThatThrownBy(() -> reversed.validate(Duration.ofDays(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overlong.validate(Duration.ofDays(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesMissingWindowToPreviousSevenDays() {
        OperationsAnomalyQuery query = new OperationsAnomalyQuery(
                null, null, "B1", "HIGH", null, null, null);

        OperationsAnomalyQuery normalized = query.normalized(Instant.parse("2026-09-03T12:00:00Z"));

        assertThat(normalized.from()).isEqualTo(Instant.parse("2026-08-27T12:00:00Z"));
        assertThat(normalized.to()).isEqualTo(Instant.parse("2026-09-03T12:00:00Z"));
        assertThat(normalized.buildingId()).isEqualTo("B1");
        assertThat(normalized.riskLevel()).isEqualTo("HIGH");
    }
}
