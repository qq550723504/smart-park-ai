package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsAnomalyConfigurationTest {
    @Test
    void registersAnomalyReadersOnlyInsideTheAnalyticsCapability() throws Exception {
        String configuration = Files.readString(Path.of("src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java"));

        assertThat(configuration).contains("JdbcAlertAnalyticsReader", "JdbcDeviceAnalyticsReader", "JdbcEnergyAnalyticsReader");
        assertThat(configuration).contains("@ConditionalOnProperty(name = \"smartpark.analytics.enabled\", havingValue = \"true\")");
    }
}
