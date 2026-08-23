package com.example.smartpark.model;

import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisTest {

    @ParameterizedTest
    @MethodSource("nonFiniteConfidences")
    void nonFiniteConfidenceIsRejected(double confidence) {
        assertThatThrownBy(() -> new Diagnosis(
                "diag-test",
                "ALT-TEST-001",
                "DEV-TEST-001",
                RiskLevel.LOW,
                "restricted airflow",
                "filter inspection required",
                List.of("sensor evidence"),
                "inspect filter",
                confidence,
                Instant.parse("2026-08-23T01:30:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    private static Stream<Double> nonFiniteConfidences() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }
}
