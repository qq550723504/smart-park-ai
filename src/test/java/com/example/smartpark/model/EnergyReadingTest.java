package com.example.smartpark.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyReadingTest {

    @Test
    void calculatesConsumptionVarianceAgainstTheBaseline() {
        EnergyReading reading = new EnergyReading(
                "METER-001",
                "PARK-A",
                "A2",
                Instant.parse("2026-08-23T01:00:00Z"),
                138.0,
                100.0,
                42.5);

        assertThat(reading.varianceKwh()).isEqualTo(38.0);
        assertThat(reading.varianceRatio()).isEqualTo(0.38);
    }

    @Test
    void rejectsANonPositiveBaseline() {
        assertThatThrownBy(() -> new EnergyReading(
                "METER-001",
                "PARK-A",
                "A2",
                Instant.parse("2026-08-23T01:00:00Z"),
                138.0,
                0.0,
                42.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baselineKwh");
    }
}
