package com.example.smartpark.port.knowledge;

import java.util.List;

/** A retrieved knowledge item with a stable citation for answer grounding. */
public record KnowledgeMatch(
        String citationId,
        String title,
        double score) {
    public KnowledgeMatch {
        if (citationId == null || citationId.isBlank()) throw new IllegalArgumentException("citationId must not be blank");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (!Double.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("score must be between 0 and 1");
    }
}
