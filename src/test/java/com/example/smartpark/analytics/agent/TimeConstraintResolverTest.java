package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeConstraintResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private final TimeConstraintResolver resolver = new TimeConstraintResolver();

    @Test
    void resolvesNoTimeIntentToMetricDefaultLookback() {
        TimeConstraintResolver.Resolved resolved = resolver.resolve(
                new TimeIntentResult(TimeIntentResult.Status.NONE, List.of(), null, null, ""), NOW, 7);

        assertThat(resolved.timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-17T00:00:00Z"), NOW));
        assertThat(resolved.source()).isEqualTo(QueryPlan.TimeRangeSource.DEFAULT_METRIC_LOOKBACK);
    }

    @Test
    void preservesParsedRangeAsExplicitUserRange() {
        QueryPlan.TimeRange range = new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T00:00:00Z"), NOW);
        TimeIntentResult parsed = new TimeIntentResult(
                TimeIntentResult.Status.PARSED,
                List.of(new TimeIntentResult.TimeMention("过去24小时", 0, "过去24小时".length())),
                new TimeIntent("过去24小时", TimeIntent.Kind.ROLLING, 24, TimeIntent.Unit.HOUR,
                        null, null, null),
                range, "");

        TimeConstraintResolver.Resolved resolved = resolver.resolve(parsed, NOW, 7);

        assertThat(resolved.timeRange()).isEqualTo(range);
        assertThat(resolved.source()).isEqualTo(QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE);
    }

    @Test
    void rejectsUnresolvedTimeIntentBeforePlanConstruction() {
        TimeIntentResult unsupported = new TimeIntentResult(
                TimeIntentResult.Status.UNSUPPORTED,
                List.of(new TimeIntentResult.TimeMention("今天晚上", 0, 4)),
                null, null, "时间表达式包含未消费的限定词");

        assertThatThrownBy(() -> resolver.resolve(unsupported, NOW, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED");
    }
}
