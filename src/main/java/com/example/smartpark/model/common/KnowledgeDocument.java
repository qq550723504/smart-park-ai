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

    /** @deprecated New documents must declare their domain explicitly. */
    @Deprecated(forRemoval = false)
    public KnowledgeDocument(String id, String title, String content, List<String> tags, Instant updatedAt) {
        this(id, KnowledgeDomain.CUSTOMER_SERVICE, title, content, tags, updatedAt);
    }

    public KnowledgeDocument {
        id = PublicMetadata.requireIdentifier(id, "id");
        domain = Objects.requireNonNull(domain, "domain");
        title = PublicMetadata.requireTitle(title);
        content = Objects.requireNonNull(content, "content");
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("knowledge document content must not exceed " + MAX_CONTENT_LENGTH + " characters");
        }
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
