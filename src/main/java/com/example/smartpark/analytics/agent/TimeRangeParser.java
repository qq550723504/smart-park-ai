package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Compatibility facade for callers that still consume the original parser
 * result. A provider must be injected; production wiring uses the sidecar.
 */
final class TimeRangeParser {

    private final TimeIntentProvider provider;

    TimeRangeParser() {
        this((question, now) -> {
            throw new IllegalStateException("analytics time intent provider must be configured");
        });
    }

    TimeRangeParser(TimeIntentProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    enum Status { NONE, PARSED, UNSUPPORTED, MULTIPLE }

    record ParseResult(Status status, QueryPlan.TimeRange timeRange, String expression) {
    }

    ParseResult parse(String question, Instant now) {
        TimeIntentResult result = provider.resolve(question, now);
        List<TimeIntentResult.TimeMention> mentions = result.mentions();
        String expression = mentions.stream()
                .map(TimeIntentResult.TimeMention::text)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
        if (result.status() == TimeIntentResult.Status.NONE) {
            return new ParseResult(Status.NONE, null, "");
        }
        if (result.status() == TimeIntentResult.Status.PARSED) {
            return new ParseResult(Status.PARSED, result.timeRange(), expression);
        }
        if (result.status() == TimeIntentResult.Status.MULTIPLE) {
            return new ParseResult(Status.MULTIPLE, null, expression);
        }
        return new ParseResult(Status.UNSUPPORTED, null, expression);
    }
}
