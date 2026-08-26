package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TimeRangeParserTest {

    private static final Instant NOW = Instant.parse("2026-08-24T16:00:00Z");
    private static final Instant EDGE_NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final TimeRangeParser parser = new TimeRangeParser();

    @Test
    void parsesStandaloneMonthAndYearAsCalendarRanges() {
        assertThat(parser.parse("8月能耗", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(
                        Instant.parse("2026-07-31T16:00:00Z"),
                        Instant.parse("2026-08-31T16:00:00Z")),
                "8月"));
        assertThat(parser.parse("2026年能耗", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(
                        Instant.parse("2025-12-31T16:00:00Z"),
                        Instant.parse("2026-12-31T16:00:00Z")),
                "2026年"));
    }

    @Test
    void parsesChineseDurationUnitsInsteadOfFallingBackToDefaultLookback() {
        assertThat(parser.parse("过去两周能耗", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(
                        Instant.parse("2026-08-10T16:00:00Z"), NOW),
                "过去两周"));
        assertThat(parser.parse("最近一个月能耗", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(
                        Instant.parse("2026-07-24T16:00:00Z"), NOW),
                "最近一个月"));
    }

    @Test
    void parsesQuarterAndYearExpressions() {
        assertThat(parser.parse("本季度能耗", NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-06-30T16:00:00Z"), NOW));
        assertThat(parser.parse("去年能耗", NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2024-12-31T16:00:00Z"),
                Instant.parse("2025-12-31T16:00:00Z")));
    }

    @Test
    void distinguishesNoTimeExpressionFromUnsupportedAndMultipleExpressions() {
        assertThat(parser.parse("总能耗", NOW).status()).isEqualTo(TimeRangeParser.Status.NONE);
        assertThat(parser.parse("上上月能耗", NOW).status()).isEqualTo(TimeRangeParser.Status.UNSUPPORTED);
        assertThat(parser.parse("对比本月和去年能耗", NOW).status()).isEqualTo(TimeRangeParser.Status.MULTIPLE);
    }

    @Test
    void parsesQualifiedPeriodsAndHourlyDurationsWithoutMatchingEntityDates() {
        assertThat(parser.parse("本周三能耗", EDGE_NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-25T16:00:00Z"),
                Instant.parse("2026-08-26T16:00:00Z")));
        assertThat(parser.parse("上月15日能耗", EDGE_NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-07-14T16:00:00Z"),
                Instant.parse("2026-07-15T16:00:00Z")));
        assertThat(parser.parse("过去24小时能耗", EDGE_NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T00:00:00Z"), EDGE_NOW));
        assertThat(parser.parse("近12小时告警", EDGE_NOW).timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T12:00:00Z"), EDGE_NOW));
        assertThat(parser.parse("MTR-2026-08-01表计的能耗", EDGE_NOW).status())
                .isEqualTo(TimeRangeParser.Status.NONE);
    }
}
