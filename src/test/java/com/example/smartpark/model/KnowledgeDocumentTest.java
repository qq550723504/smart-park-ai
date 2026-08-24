package com.example.smartpark.model;

import com.example.smartpark.model.common.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentTest {

    @Test
    void knowledgeDocumentsRequireAnExplicitDomain() {
        assertThatThrownBy(() -> new KnowledgeDocument(
                "KD-1", null, "Title", "body", List.of("tag"), Instant.EPOCH))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("domain");
    }
}
