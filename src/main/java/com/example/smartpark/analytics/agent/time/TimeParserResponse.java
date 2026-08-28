package com.example.smartpark.analytics.agent.time;

import java.util.List;
import java.util.Objects;

public record TimeParserResponse(String provider, String providerVersion, String referenceInstant,
                                 String timezone, String status, List<TimeParserMention> mentions,
                                 String reasonCode) {
    public TimeParserResponse {
        provider = Objects.requireNonNull(provider, "provider");
        providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        referenceInstant = Objects.requireNonNull(referenceInstant, "referenceInstant");
        timezone = Objects.requireNonNull(timezone, "timezone");
        status = Objects.requireNonNull(status, "status");
        mentions = List.copyOf(Objects.requireNonNullElse(mentions, List.of()));
    }
}
