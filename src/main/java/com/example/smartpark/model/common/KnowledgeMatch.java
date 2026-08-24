package com.example.smartpark.model.common;

import java.util.Objects;

/** A ranked knowledge hit. The document body is for internal use only. */
public record KnowledgeMatch(KnowledgeDocument document, double score) {
    public KnowledgeMatch {
        document = Objects.requireNonNull(document, "document");
        PublicMetadata.requireIdentifier(document.id(), "documentId");
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
    }

    public String documentId() { return document.id(); }
    public String title() { return document.title(); }
}
