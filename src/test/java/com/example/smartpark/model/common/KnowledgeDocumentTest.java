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
