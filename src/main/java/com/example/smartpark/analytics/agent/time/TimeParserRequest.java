package com.example.smartpark.analytics.agent.time;

import java.util.List;
import java.util.Objects;

public record TimeParserRequest(String question, String referenceInstant, String timezone,
                                List<UnicodeOffsetMapper.Span> excludedSpans) {
    public TimeParserRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        Objects.requireNonNull(referenceInstant, "referenceInstant");
        Objects.requireNonNull(timezone, "timezone");
        excludedSpans = List.copyOf(Objects.requireNonNullElse(excludedSpans, List.of()));
    }
}
