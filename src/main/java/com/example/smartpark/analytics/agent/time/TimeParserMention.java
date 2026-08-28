package com.example.smartpark.analytics.agent.time;

import java.util.Objects;

public record TimeParserMention(String text, int start, int end, String type, String definition,
                                String fromInclusive, String toExclusive, boolean empty) {
    public TimeParserMention {
        if (text == null || text.isBlank() || start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid parser mention");
        }
        type = Objects.requireNonNullElse(type, "");
        definition = Objects.requireNonNullElse(definition, "");
    }
}
