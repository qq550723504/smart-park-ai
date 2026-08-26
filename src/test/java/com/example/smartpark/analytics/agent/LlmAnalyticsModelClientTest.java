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
                  "requestedDimensions": ["building_id"],
                  "requestedTimeMentions": ["过去30天"]
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        var understanding = client.understandQuestion("过去30天各楼宇能耗");

        assertThat(understanding.requestedTimeMentions()).containsExactly("过去30天");
        assertThat(understanding.requestedDimensions()).containsExactly("building_id");
        assertThat(understanding.requestedTimeRange()).isNull();
        assertThat(model.lastPrompt().getSystemMessage().getText())
                .contains("2026-08-24T00:00:00Z", "Asia/Shanghai", "requestedTimeMentions");
    }

    @Test
    void understandingContractIncludesTypedEntityFilters() throws Exception {
        var accessor = AnalyticsModelClient.QuestionUnderstanding.class.getMethod("requestedFilters");
        assertThat(accessor.getReturnType()).isEqualTo(java.util.Map.class);
    }

    @Test
    void parsesEntityFiltersAsAStringMap() {
        TestChatModel model = new TestChatModel("""
                {
                  "normalizedQuestion": "B1楼宇的能耗",
                  "metricTerms": ["energy_kwh"],
                  "clarificationQuestions": [],
                  "requestedDimensions": [],
                  "requestedFilters": {"building_id": "B1"},
                  "requestedTimeRange": null
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        var understanding = client.understandQuestion("B1楼宇的能耗");

        assertThat(understanding.requestedFilters()).containsExactlyEntriesOf(
                java.util.Map.of("building_id", "B1"));
        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("requestedFilters");
    }

    @Test
    void preservesTheOriginalQuestionWhenTheModelDropsEntityScope() {
        TestChatModel model = new TestChatModel("""
                {
                  "normalizedQuestion": "能耗",
                  "metricTerms": ["energy_kwh"],
                  "clarificationQuestions": [],
                  "requestedDimensions": [],
                  "requestedFilters": {},
                  "requestedTimeRange": null
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        var understanding = client.understandQuestion("  B1楼宇的能耗  ");

        assertThat(understanding.normalizedQuestion()).isEqualTo("B1楼宇的能耗");
    }

    @Test
    void keepsRequestedTimeMentionsAsVerbatimFragments() {
        TestChatModel model = new TestChatModel("""
                {
                  "normalizedQuestion": "上周各楼宇能耗",
                  "metricTerms": ["energy_consumption"],
                  "clarificationQuestions": [],
                  "requestedTimeMentions": ["上周"]
                }
                """);
        var client = new LlmAnalyticsModelClient(
                model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));

        // 逐字校验由 ModelTimeEvidence 在原问题中定位时进行，客户端只负责透传。
        var understanding = client.understandQuestion("上周各楼宇能耗");

        assertThat(understanding.requestedTimeMentions()).containsExactly("上周");
    }

    @Test
    void sqlPromptAdvertisesThePlanRowLimit() {
        String prompt = LlmAnalyticsModelClient.sqlSystemPrompt(200);

        assertThat(prompt).contains("LIMIT");
        assertThat(prompt).contains("200");
        assertThat(prompt).doesNotContain("500");
    }
}
