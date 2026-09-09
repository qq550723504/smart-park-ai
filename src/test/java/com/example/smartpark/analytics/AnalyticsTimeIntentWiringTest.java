package com.example.smartpark.analytics;

import com.example.smartpark.analytics.report.OperationsReportRequest;
import com.example.smartpark.analytics.report.OperationsReportSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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

    @Test
    void reportSectionQuestionUsesTheAtomicUtcRangeAcceptedByTheGovernedParser() {
        OperationsReportSection section = new OperationsReportSection(
                "energy", "能耗", "过去5天各楼宇能耗基线偏差");
        OperationsReportRequest request = new OperationsReportRequest(
                OperationsReportRequest.DAILY,
                new OperationsReportRequest.TimeWindow(
                        Instant.parse("2026-09-04T01:00:00Z"),
                        Instant.parse("2026-09-09T01:00:00Z")),
                "Asia/Shanghai");

        assertThat(AnalyticsConfiguration.operationsReportQuestion(section, request))
                .isEqualTo("2026-09-04T01:00:00Z 到 2026-09-09T01:00:00Z 各楼宇能耗基线偏差");
    }
}
