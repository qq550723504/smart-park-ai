package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelistTimeIntentProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-24T16:00:00Z"); // 8月25日 00:00 +08
    private final WhitelistTimeIntentProvider provider = new WhitelistTimeIntentProvider();

    @Test
    void parsesDayPartWithoutFallingBackToTheWholeDay() {
        TimeIntentResult result = provider.resolve("今天上午能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-24T23:00:00Z"), Instant.parse("2026-08-25T04:00:00Z")));
    }

    @Test
    void resolvesEveningDayPartInsteadOfRefusingIt() {
        TimeIntentResult result = provider.resolve("今天晚上能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-25T10:00:00Z"), Instant.parse("2026-08-25T16:00:00Z")));
    }

    @Test
    void parsesMonthDayWrittenWithTheChineseHaoSuffix() {
        TimeIntentResult result = provider.resolve("本月15号能耗", NOW.minusSeconds(60 * 60 * 24 * 10));

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-14T16:00:00Z"), Instant.parse("2026-08-15T16:00:00Z")));
    }

    @Test
    void parsesHourlyDurationWithAnOptionalGeSuffix() {
        TimeIntentResult result = provider.resolve("过去24个小时能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T16:00:00Z"), NOW));
    }

    @Test
    void parsesYearQualifiedHalfYearAsOneCalendarRange() {
        TimeIntentResult result = provider.resolve("2026年上半年能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2025-12-31T16:00:00Z"),
                Instant.parse("2026-06-30T16:00:00Z")));
    }

    @Test
    void parsesComposedWeekRangeFromLastWeekMondayToWednesday() {
        TimeIntentResult result = provider.resolve("上周一到周三能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.mentions()).extracting(TimeIntentResult.TimeMention::text)
                .containsExactly("上周一到周三");
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-16T16:00:00Z"), Instant.parse("2026-08-19T16:00:00Z")));
    }

    @Test
    void parsesComposedWeekRangeWithinCurrentWeek() {
        TimeIntentResult result = provider.resolve("周一到周三能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T16:00:00Z"), Instant.parse("2026-08-26T16:00:00Z")));
    }

    @Test
    void parsesComposedWeekRangeSpanningWeeksWithExplicitQualifiers() {
        TimeIntentResult result = provider.resolve("上周五到本周一能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-20T16:00:00Z"), Instant.parse("2026-08-24T16:00:00Z")));
    }

    @Test
    void rejectsReversedComposedWeekRange() {
        TimeIntentResult result = provider.resolve("本周三到本周一能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }

    @Test
    void keepsEntityDatesOutOfTemporalMentions() {
        assertThat(provider.resolve("MTR-2026-08-01表计的能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.NONE);
    }

    @Test
    void rejectsTwoIndependentRanges() {
        assertThat(provider.resolve("对比本月和去年能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.MULTIPLE);
    }

    @Test
    void rejectsIncompleteHalfUnitDurationsInsteadOfTruncatingThem() {
        assertThat(provider.resolve("近一年半能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }

    @Test
    void acceptsRepeatedEquivalentRangesAsOneSharedConstraint() {
        TimeIntentResult result = provider.resolve("本月能耗与本月基线偏差", NOW.plusSeconds(3600));

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.mentions()).hasSize(2);
        assertThat(result.timeRange().from())
                .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
    }

    @Test
    void capsOngoingCurrentPeriodAtTheReferenceInstant() {
        TimeIntentResult result = provider.resolve("本周能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T16:00:00Z"), NOW));
    }

    @Test
    void reportsEmptyWhenTheReferenceInstantIsExactlyThePeriodStart() {
        // 2026-08-25 00:00 本地零点即“今天”的周期开始 → [t, t)
        TimeIntentResult result = provider.resolve("今天能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.EMPTY);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(NOW, NOW));
    }

    @Test
    void keepsFullCalendarWindowForCompletedPeriods() {
        TimeIntentResult result = provider.resolve("上周能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-16T16:00:00Z"), Instant.parse("2026-08-23T16:00:00Z")));
    }

    @Test
    void parsesComposedMonthDayRangeAsOneContinuousSpan() {
        TimeIntentResult result = provider.resolve("8月1日到8月3日能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-07-31T16:00:00Z"), Instant.parse("2026-08-03T16:00:00Z")));
    }

    @Test
    void refusesReversedComposedRangesInsteadOfGuessing() {
        assertThat(provider.resolve("8月5日到8月1日能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }
}
