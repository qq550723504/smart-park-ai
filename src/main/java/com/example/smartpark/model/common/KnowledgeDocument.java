package com.example.smartpark.model.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record KnowledgeDocument(
        String id,
        KnowledgeDomain domain,
        String title,
        String content,
        List<String> tags,
        Instant updatedAt) {

    public static final int MAX_CONTENT_LENGTH = 2_000;
    public static final int MAX_TAG_COUNT = 32;
    public static final int MAX_TAG_LENGTH = 80;
    public static final int MAX_TAGS_LENGTH = 512;

    public KnowledgeDocument {
        id = PublicMetadata.requireIdentifier(id, "id");
        domain = Objects.requireNonNull(domain, "domain");
        title = PublicMetadata.requireTitle(title);
        content = Objects.requireNonNull(content, "content");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("knowledge document content must not exceed " + MAX_CONTENT_LENGTH + " characters");
        }
        tags = validateTags(tags);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static List<String> validateTags(List<String> values) {
        Objects.requireNonNull(values, "tags");
        if (values.isEmpty() || values.size() > MAX_TAG_COUNT) {
            throw new IllegalArgumentException("knowledge tags must contain between 1 and " + MAX_TAG_COUNT + " items");
        }
        int totalLength = 0;
        for (String tag : values) {
            if (!PublicMetadata.isSafePublicText(tag) || tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("knowledge tag must be bounded public metadata and must not exceed " + MAX_TAG_LENGTH + " characters");
            }
            totalLength += tag.length();
        }
        totalLength += values.size() - 1;
        if (totalLength > MAX_TAGS_LENGTH) {
            throw new IllegalArgumentException("knowledge tags must not exceed " + MAX_TAGS_LENGTH + " characters in total");
        }
        return List.copyOf(values);
    }
}
