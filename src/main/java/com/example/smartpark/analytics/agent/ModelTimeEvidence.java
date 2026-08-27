package com.example.smartpark.analytics.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Model-supplied time mentions located in the original question. The model
 * only ever names verbatim substrings; this class owns the UTF-16 offset
 * arithmetic so no downstream stage trusts model-computed positions.
 */
record ModelTimeEvidence(List<TimeIntentResult.TimeMention> mentions) {

    /** Hard ceiling mirroring the Python sidecar's MAX_EXCLUDED_SPANS guard. */
    private static final int MAX_MENTIONS = 32;

    ModelTimeEvidence {
        mentions = List.copyOf(Objects.requireNonNull(mentions, "mentions"));
    }

    /**
     * Locates every exact occurrence of every mention. A mention that does
     * not appear verbatim makes the whole understanding response invalid —
     * surfaced as UNSUPPORTED by the reconciler, never silently dropped.
     */
    static ModelTimeEvidence fromQuestion(List<String> requestedMentions,
                                          String question) {
        List<TimeIntentResult.TimeMention> located = new ArrayList<>();
        String normalized = question == null ? "" : question;
        List<String> safeMentions = Objects.requireNonNullElse(requestedMentions, List.<String>of());
        if (safeMentions.size() > MAX_MENTIONS) {
            throw new IllegalArgumentException(
                    "too many time mentions supplied by the model: " + safeMentions.size());
        }
        for (String mention : safeMentions) {
            if (mention == null || mention.isBlank()) {
                throw new IllegalArgumentException("time mention must not be blank");
            }
            int index = normalized.indexOf(mention);
            if (index < 0) {
                throw new IllegalArgumentException(
                        "model time mention is not verbatim in the question: " + mention);
            }
            // Deterministic ordering: first occurrence, mentions in given order.
            while (index >= 0) {
                located.add(new TimeIntentResult.TimeMention(mention, index,
                        index + mention.length()));
                index = normalized.indexOf(mention, index + 1);
            }
        }
        return new ModelTimeEvidence(located);
    }

    boolean isEmpty() {
        return mentions.isEmpty();
    }
}
