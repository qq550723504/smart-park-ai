package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;

import java.util.List;
import java.util.Objects;

record TimeIntentResult(
        Status status,
        List<TimeMention> mentions,
        TimeIntent intent,
        QueryPlan.TimeRange timeRange,
        String reason) {

    TimeIntentResult {
        Objects.requireNonNull(status, "status");
        mentions = List.copyOf(Objects.requireNonNull(mentions, "mentions"));
        reason = Objects.requireNonNullElse(reason, "");
        if (status == Status.PARSED && intent == null) {
            throw new IllegalArgumentException("PARSED result requires an intent");
        }
        if (status == Status.PARSED && timeRange == null) {
            throw new IllegalArgumentException("PARSED result requires a timeRange");
        }
        if (status != Status.PARSED && (intent != null || timeRange != null)) {
            throw new IllegalArgumentException("non-parsed result must not carry a resolved payload");
        }
    }

    enum Status { NONE, PARSED, UNSUPPORTED, MULTIPLE, AMBIGUOUS }

    record TimeMention(String text, int start, int end) {
        TimeMention {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("mention text must not be blank");
            }
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("mention span must be ordered");
            }
            if (end - start != text.length()) {
                throw new IllegalArgumentException("mention span must match text length");
            }
        }
    }
}
