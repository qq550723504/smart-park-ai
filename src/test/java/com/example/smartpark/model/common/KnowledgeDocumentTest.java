package com.example.smartpark.model.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentTest {

    @Test
    void rejectsControlCharactersInPublicTitleAtTheDomainBoundary() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-INVALID-001", "Parking guide\ninternal", "Body", List.of("parking"), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded public metadata");
    }
}
