package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FiniteGrammarTimeIntentProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-24T16:00:00Z");
    private final FiniteGrammarTimeIntentProvider provider = new FiniteGrammarTimeIntentProvider();

    @Test
    void parsesDayPartWithoutFallingBackToTheWholeDay() {
        TimeIntentResult result = provider.resolve("今天上午能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-24T16:00:00Z"),
                Instant.parse("2026-08-25T04:00:00Z")));
    }

    @Test
    void parsesMonthDayWrittenWithTheChineseHaoSuffix() {
        TimeIntentResult result = provider.resolve("本月15号能耗", NOW);

        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-14T16:00:00Z"),
                Instant.parse("2026-08-15T16:00:00Z")));
    }

    @Test
    void parsesHourlyDurationWithAnOptionalGeSuffix() {
        TimeIntentResult result = provider.resolve("过去24个小时能耗", NOW);

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
    void keepsTheOriginalMentionAsTheIntentSource() {
        TimeIntentResult result = provider.resolve("2026年8月1日能耗", NOW);

        assertThat(result.intent().sourceText()).isEqualTo("2026年8月1日");
    }

    @Test
    void rejectsAResidualDayPartInsteadOfReturningTheBaseDay() {
        assertThat(provider.resolve("今天晚上能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
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
        assertThat(provider.resolve("过去半年能耗", NOW).status())
                .isEqualTo(TimeIntentResult.Status.UNSUPPORTED);
    }

    @Test
    void acceptsRepeatedEquivalentRangesAsOneSharedConstraint() {
        TimeIntentResult result = provider.resolve("本月能耗与本月基线偏差", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.mentions()).hasSize(2);
        assertThat(result.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-07-31T16:00:00Z"), NOW));
    }
}
