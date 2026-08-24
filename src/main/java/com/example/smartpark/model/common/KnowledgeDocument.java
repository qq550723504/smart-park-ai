package com.example.smartpark.model.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record KnowledgeDocument(
        String id,
        String title,
        String content,
        List<String> tags,
        Instant updatedAt) {

    private static final int MAX_PUBLIC_TITLE_LENGTH = 160;

    public KnowledgeDocument {
        id = Objects.requireNonNull(id, "id");
        title = requirePublicTitle(title);
        content = Objects.requireNonNull(content, "content");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requirePublicTitle(String value) {
        Objects.requireNonNull(value, "title");
        if (value.isBlank() || value.length() > MAX_PUBLIC_TITLE_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("title must be bounded public metadata");
        }
        return value;
    }
}
