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

    public KnowledgeDocument {
        id = Objects.requireNonNull(id, "id");
        domain = Objects.requireNonNull(domain, "domain");
        title = Objects.requireNonNull(title, "title");
        content = Objects.requireNonNull(content, "content");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
