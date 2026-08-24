package com.example.smartpark.model.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentTest {

    @Test
    void rejectsUnsafeDocumentIdentifiersAtTheDomainBoundary() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-INVALID\n001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "Body", List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe opaque identifier");

        assertThatThrownBy(() -> new KnowledgeDocument(
                "a".repeat(129), KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "Body", List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe opaque identifier");
    }

    @Test
    void rejectsOverlongKnowledgeContentAtTheDomainBoundary() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-LONG-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "x".repeat(2_001),
                List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge document content");
    }

    @Test
    void rejectsOversizedKnowledgeTagsAtTheDomainBoundary() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-TAGS-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "Body",
                List.of("x".repeat(81)), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge tag");

        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-TAGS-002", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "Body",
                java.util.stream.IntStream.range(0, 33).mapToObj(index -> "tag-" + index).toList(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge tags");

        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-TAGS-003", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide", "Body",
                java.util.stream.IntStream.range(0, 7).mapToObj(index -> "x".repeat(80)).toList(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in total");
    }

    @Test
    void rejectsControlCharactersInPublicTitleAtTheDomainBoundary() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-INVALID-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide\ninternal", "Body", List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded public metadata");

        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-INVALID-002", KnowledgeDomain.CUSTOMER_SERVICE, "Parking guide\u2029internal", "Body", List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded public metadata");
    }
}
