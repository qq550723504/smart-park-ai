package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class TimeConstraintResolver {

    Resolved resolve(TimeIntentResult result, Instant now, int lookbackDays) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(now, "now");
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("lookbackDays must be positive");
        }
        return switch (result.status()) {
            case NONE -> new Resolved(
                    new QueryPlan.TimeRange(now.minus(Duration.ofDays(lookbackDays)), now),
                    QueryPlan.TimeRangeSource.DEFAULT_METRIC_LOOKBACK);
            case PARSED -> new Resolved(result.timeRange(), QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE);
            case EMPTY -> throw new IllegalArgumentException(
                    "zero-width current period must be terminated before query planning");
            case UNSUPPORTED, MULTIPLE, AMBIGUOUS -> throw new IllegalArgumentException(
                    "time intent is unresolved: " + result.status() + ": " + result.reason());
        };
    }

    record Resolved(QueryPlan.TimeRange timeRange, QueryPlan.TimeRangeSource source) {
        Resolved {
            Objects.requireNonNull(timeRange, "timeRange");
            Objects.requireNonNull(source, "source");
        }
    }
}
