package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeRangeParserTest {

    private static final Instant NOW = Instant.parse("2026-08-24T16:00:00Z");
    private static final Instant EDGE_NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final TimeRangeParser parser = new TimeRangeParser(TimeRangeParserTest::fixtureProvider);

    private static TimeIntentResult fixtureProvider(String question, Instant now) {
        String expression = switch (question) {
            case "8月能耗" -> "8月";
            case "2026年能耗" -> "2026年";
            case "过去两周能耗" -> "过去两周";
            case "最近一个月能耗" -> "最近一个月";
            case "过去1年能耗" -> "过去1年";
            case "近两年告警" -> "近两年";
            case "本季度能耗" -> "本季度";
            case "去年能耗" -> "去年";
            case "本周三能耗" -> "本周三";
            case "上月15日能耗" -> "上月15日";
            case "过去24小时能耗" -> "过去24小时";
            case "近12小时告警" -> "近12小时";
            default -> null;
        };
        if ("总能耗".equals(question) || question.startsWith("MTR-")) {
            return new TimeIntentResult(TimeIntentResult.Status.NONE, List.of(), null, null, "");
        }
        if ("上上月能耗".equals(question)) {
            return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED,
                    List.of(new TimeIntentResult.TimeMention("上上月", 0, 3)), null, null, "");
        }
        if ("对比本月和去年能耗".equals(question)) {
            return new TimeIntentResult(TimeIntentResult.Status.MULTIPLE, List.of(
                    new TimeIntentResult.TimeMention("本月", 2, 4),
                    new TimeIntentResult.TimeMention("去年", 5, 7)), null, null, "");
        }
        QueryPlan.TimeRange range = switch (expression) {
            case "8月" -> new QueryPlan.TimeRange(Instant.parse("2026-07-31T16:00:00Z"), Instant.parse("2026-08-31T16:00:00Z"));
            case "2026年" -> new QueryPlan.TimeRange(Instant.parse("2025-12-31T16:00:00Z"), Instant.parse("2026-12-31T16:00:00Z"));
            case "过去两周" -> new QueryPlan.TimeRange(now.minusSeconds(14 * 86400L), now);
            case "最近一个月" -> new QueryPlan.TimeRange(Instant.parse("2026-07-24T16:00:00Z"), now);
            case "过去1年" -> new QueryPlan.TimeRange(Instant.parse("2025-08-24T16:00:00Z"), now);
            case "近两年" -> new QueryPlan.TimeRange(Instant.parse("2024-08-24T16:00:00Z"), now);
            case "本季度" -> new QueryPlan.TimeRange(Instant.parse("2026-06-30T16:00:00Z"), now);
            case "去年" -> new QueryPlan.TimeRange(Instant.parse("2024-12-31T16:00:00Z"), Instant.parse("2025-12-31T16:00:00Z"));
            case "本周三" -> new QueryPlan.TimeRange(Instant.parse("2026-08-25T16:00:00Z"), Instant.parse("2026-08-26T16:00:00Z"));
            case "上月15日" -> new QueryPlan.TimeRange(Instant.parse("2026-07-14T16:00:00Z"), Instant.parse("2026-07-15T16:00:00Z"));
            case "过去24小时" -> new QueryPlan.TimeRange(now.minusSeconds(86400), now);
            case "近12小时" -> new QueryPlan.TimeRange(now.minusSeconds(43200), now);
            default -> throw new AssertionError("missing fixture: " + question);
        };
        return new TimeIntentResult(TimeIntentResult.Status.PARSED,
                List.of(new TimeIntentResult.TimeMention(expression, question.indexOf(expression),
                        question.indexOf(expression) + expression.length())),
                new TimeIntent(expression, TimeIntent.Kind.DATE_RANGE, 0, null,
                        range.from().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),
                        range.to().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(), null),
                range, "");
    }

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
        assertThat(parser.parse("过去1年能耗", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(Instant.parse("2025-08-24T16:00:00Z"), NOW),
                "过去1年"));
        assertThat(parser.parse("近两年告警", NOW)).isEqualTo(new TimeRangeParser.ParseResult(
                TimeRangeParser.Status.PARSED,
                new QueryPlan.TimeRange(Instant.parse("2024-08-24T16:00:00Z"), NOW),
                "近两年"));
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
