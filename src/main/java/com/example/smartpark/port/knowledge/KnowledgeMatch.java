package com.example.smartpark.port.knowledge;

import java.util.regex.Pattern;

/** A retrieved knowledge item with a stable citation for answer grounding. */
public record KnowledgeMatch(
        String citationId,
        String title,
        double score) {
    private static final Pattern SAFE_CITATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final int MAX_PUBLIC_TITLE_LENGTH = 160;

    public KnowledgeMatch {
        if (citationId == null || !SAFE_CITATION_ID.matcher(citationId).matches()) {
            throw new IllegalArgumentException("citationId must be a safe opaque identifier");
        }
        if (title == null || title.isBlank() || title.length() > MAX_PUBLIC_TITLE_LENGTH
                || title.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("title must be bounded public metadata");
        }
        if (!Double.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("score must be between 0 and 1");
    }
}
