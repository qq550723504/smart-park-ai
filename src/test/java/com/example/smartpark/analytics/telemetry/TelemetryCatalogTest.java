package com.example.smartpark.analytics.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryCatalogTest {
    @Test
    void registersOnlyTruthfulCapabilityStatesAndThresholdProvenance() {
        TelemetryCatalog catalog = new TelemetryCatalog();

        var temperature = catalog.resolve("temperature");
        assertThat(temperature.capabilityStatus()).isEqualTo(TelemetryCatalog.CapabilityStatus.ADAPTED);
        assertThat(temperature.unit()).isEqualTo("°C");
        assertThat(temperature.datasetKind()).isEqualTo("DEMO");
        assertThat(temperature.threshold().source()).startsWith("DEMO_POLICY:");
        assertThat(catalog.resolve("VIBRATION").capabilityStatus())
                .isEqualTo(TelemetryCatalog.CapabilityStatus.NOT_READY);
        assertThatThrownBy(() -> catalog.resolve("whatever"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
