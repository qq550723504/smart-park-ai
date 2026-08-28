package com.example.smartpark.analytics.agent;

import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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

    @Test
    void rendersSupportedPlanWithoutCallingModelForSql() {
        TestChatModel model = new TestChatModel();
        var client = new LlmAnalyticsModelClient(model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));
        var metric = new MetricCatalog().findByName("energy_kwh").orElseThrow();
        var plan = new QueryPlan("过去5天各楼宇能耗", List.of(metric), List.of("building_id"), Map.of(),
                new QueryPlan.TimeRange(NOW.minusSeconds(5 * 24 * 3600L), NOW), 200);

        String sql = client.generateSql(new AnalyticsModelClient.SqlGenerationRequest(plan, "", null));

        assertThat(sql).contains("SUM(kwh) AS energy_kwh", "GROUP BY building_id", "LIMIT 200");
        assertThat(model.callCount()).isZero();
    }

    @Test
    void parsesExtendedChartProposalOptions() {
        TestChatModel model = new TestChatModel("""
                {"type":"MAP","title":"楼宇分布","xField":"building_name",
                 "yFields":["energy_kwh"],"seriesField":"","unit":"kWh",
                 "orientation":"VERTICAL","stacked":false,"targetValue":null,
                 "coordinateXField":"map_x","coordinateYField":"map_y"}
                """);
        var client = new LlmAnalyticsModelClient(model, new MetricCatalog(), Clock.fixed(NOW, ZoneOffset.UTC));
        var metric = new MetricCatalog().findByName("energy_kwh").orElseThrow();
        var plan = new QueryPlan("楼宇分布", List.of(metric), List.of("building_name", "map_x", "map_y"),
                Map.of(), new QueryPlan.TimeRange(NOW.minusSeconds(3600), NOW), 200);
        var proposal = client.proposeChart(new AnalyticsModelClient.ChartContext("楼宇分布", plan,
                new TabularResult(List.of("building_name", "map_x", "map_y", "energy_kwh"),
                        List.of(List.of("创新中心", 12.5, 35.0, 100)), false, 1)));

        assertThat(proposal.type()).isEqualTo("MAP");
        assertThat(proposal.options().coordinateXField()).isEqualTo("map_x");
        assertThat(proposal.options().coordinateYField()).isEqualTo("map_y");
    }
}
