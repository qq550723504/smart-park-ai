package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagKnowledgeAdminIndexTest {
    @Test
    void rejectsDuplicateSeedIdentifiersBeforeBuildingTheIndex() {
        KnowledgeDocument first = new KnowledgeDocument(
                "KB-DUPLICATE-001", KnowledgeDomain.CUSTOMER_SERVICE, "First", "parking", List.of("parking"), Instant.EPOCH);
        KnowledgeDocument second = new KnowledgeDocument(
                "KB-DUPLICATE-001", KnowledgeDomain.ALERT_OPERATIONS, "Second", "power", List.of("power"), Instant.EPOCH);

        assertThatThrownBy(() -> new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()), List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate knowledge document id");
    }

    @Test
    void saveAndActivationChangesAreImmediatelyVisibleToSearch() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()), List.of(
                new KnowledgeDocument("KB-INITIAL-001", KnowledgeDomain.CUSTOMER_SERVICE, "Initial", "parking", List.of("parking"), Instant.EPOCH)));
        KnowledgeDocument added = new KnowledgeDocument("KB-NEW-001", KnowledgeDomain.CUSTOMER_SERVICE, "New parking guide", "parking", List.of("parking"), Instant.EPOCH);

        adapter.save(added);
        assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).extracting(KnowledgeDocument::id).contains("KB-NEW-001");

        adapter.setActive("KB-NEW-001", false);
        assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).extracting(KnowledgeDocument::id).doesNotContain("KB-NEW-001");
        assertThat(adapter.list()).anyMatch(item -> item.document().id().equals("KB-NEW-001") && !item.active());
    }

    private static final class KeywordEmbeddingModel implements EmbeddingModel {
        @Override public float[] embed(Document document) { return embed(document.getText()); }
        @Override public float[] embed(String text) { return new float[] { text.contains("parking") ? 1 : 0, .1f }; }
        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
    }

    private static Map<KnowledgeDomain, org.springframework.ai.vectorstore.VectorStore> stores(EmbeddingModel model) {
        return Map.of(
                KnowledgeDomain.CUSTOMER_SERVICE, SimpleVectorStore.builder(model).build(),
                KnowledgeDomain.ALERT_OPERATIONS, SimpleVectorStore.builder(model).build());
    }
}
