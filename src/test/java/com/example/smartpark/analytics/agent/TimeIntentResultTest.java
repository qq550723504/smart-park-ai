package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeIntentResultTest {

    private static final QueryPlan.TimeRange RANGE = new QueryPlan.TimeRange(
            Instant.parse("2026-08-23T16:00:00Z"),
            Instant.parse("2026-08-24T16:00:00Z"));

    @Test
    void preservesNoTimeResultWithoutInventingAnIntent() {
        TimeIntentResult result = new TimeIntentResult(
                TimeIntentResult.Status.NONE, List.of(), null, null, "");

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.NONE);
        assertThat(result.intent()).isNull();
        assertThat(result.timeRange()).isNull();
    }

    @Test
    void rejectsRollingIntentWithNonPositiveAmount() {
        assertThatThrownBy(() -> new TimeIntent(
                "过去24小时", TimeIntent.Kind.ROLLING, 0, TimeIntent.Unit.HOUR,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsAbsoluteRangeWithoutBothDateEndpoints() {
        assertThatThrownBy(() -> new TimeIntent(
                "2026年8月", TimeIntent.Kind.DATE_RANGE, 0, null,
                LocalDate.of(2026, 8, 1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
    }

    @Test
    void preservesMentionSpanAndRequiresParsedPayload() {
        TimeIntentResult.TimeMention mention = new TimeIntentResult.TimeMention("过去24小时", 0, 6);
        TimeIntent intent = new TimeIntent(
                "过去24小时", TimeIntent.Kind.ROLLING, 24, TimeIntent.Unit.HOUR,
                null, null, null);
        TimeIntentResult result = new TimeIntentResult(
                TimeIntentResult.Status.PARSED, List.of(mention), intent, RANGE, "");

        assertThat(result.mentions()).containsExactly(mention);
        assertThat(result.intent()).isEqualTo(intent);
        assertThat(result.timeRange()).isEqualTo(RANGE);
    }

    @Test
    void rejectsParsedResultWithoutIntentOrRange() {
        assertThatThrownBy(() -> new TimeIntentResult(
                TimeIntentResult.Status.PARSED, List.of(), null, RANGE, "missing intent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intent");
        assertThatThrownBy(() -> new TimeIntentResult(
                TimeIntentResult.Status.PARSED, List.of(),
                new TimeIntent("过去24小时", TimeIntent.Kind.ROLLING, 24, TimeIntent.Unit.HOUR,
                        null, null, null), null, "missing range"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeRange");
    }
}
