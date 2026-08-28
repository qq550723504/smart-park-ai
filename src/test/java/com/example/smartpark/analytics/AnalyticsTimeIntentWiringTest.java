package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsTimeIntentWiringTest {

    @Test
    void productionAnalyticsGraphUsesJioNlpProviderWithoutWhitelistFallback() throws Exception {
        String configuration = Files.readString(Path.of(
                "src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java"));
        String graph = Files.readString(Path.of(
                "src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        Path productionAgent = Path.of("src/main/java/com/example/smartpark/analytics/agent");

        assertThat(configuration).contains("JioNlpTimeIntentProvider", "jioNlpClient");
        assertThat(configuration).doesNotContain("new WhitelistTimeIntentProvider");
        assertThat(graph).contains("failClosedProvider");
        assertThat(application).contains("SMARTPARK_ANALYTICS_TIME_INTENT_URL");
        assertThat(Files.exists(productionAgent.resolve("WhitelistTimeIntentProvider.java"))).isFalse();
        assertThat(Files.exists(productionAgent.resolve("FiniteGrammarTimeIntentProvider.java"))).isFalse();
    }
}
