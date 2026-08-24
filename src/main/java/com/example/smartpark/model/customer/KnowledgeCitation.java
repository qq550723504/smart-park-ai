package com.example.smartpark.model.customer;

import com.example.smartpark.model.common.PublicMetadata;

/** Public-safe knowledge metadata. Knowledge content must never be included here. */
public record KnowledgeCitation(String documentId, String title, double score) {
    public KnowledgeCitation {
        PublicMetadata.requireIdentifier(documentId, "documentId");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        if (!Double.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("score must be between 0 and 1");
        documentId = documentId.trim();
        title = title.trim();
    }
}
