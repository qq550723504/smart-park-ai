package com.example.smartpark.analytics.agent;

import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAnalyticsModelClientTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void parsesAbsoluteRequestedTimeRangeFromStructuredUnderstanding() {
        TestChatModel model = new TestChatModel("""
                {
                  "normalizedQuestion": "过去30天各楼宇能耗",
                  "metricTerms": ["energy_consumption"],
                  "clarificationQuestions": [],
                  "requestedTimeRange": {
                    "fromInclusive": "2026-07-25T00:00:00Z",
                    "toExclusive": "2026-08-24T00:00:00Z"
                  }
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        var understanding = client.understandQuestion("过去30天各楼宇能耗");

        assertThat(understanding.requestedTimeRange()).isEqualTo(
                new AnalyticsModelClient.RequestedTimeRange(
                        Instant.parse("2026-07-25T00:00:00Z"), NOW));
        assertThat(model.lastPrompt().getSystemMessage().getText())
                .contains("2026-08-24T00:00:00Z", "Asia/Shanghai", "requestedTimeRange");
    }

    @Test
    void rejectsPartialRequestedTimeRangeInsteadOfSilentlyUsingDefault() {
        TestChatModel model = new TestChatModel("""
                {
                  "normalizedQuestion": "过去30天各楼宇能耗",
                  "metricTerms": ["energy_consumption"],
                  "clarificationQuestions": [],
                  "requestedTimeRange": {"fromInclusive": "2026-07-25T00:00:00Z"}
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> client.understandQuestion("过去30天各楼宇能耗"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestedTimeRange");
    }
}
