package com.example.smartpark.model.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeMatchTest {
    @Test
    void rejectsUnsafeDocumentIdsAtTheRankedMatchBoundary() {
        assertThatThrownBy(() -> new KnowledgeMatch(
                new KnowledgeDocument("KD\nPRIVATE", KnowledgeDomain.CUSTOMER_SERVICE,
                        "Safe title", "body", List.of("tag"), Instant.EPOCH), .8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeMatch(
                new KnowledgeDocument("a".repeat(129), KnowledgeDomain.CUSTOMER_SERVICE,
                        "Safe title", "body", List.of("tag"), Instant.EPOCH), .8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
