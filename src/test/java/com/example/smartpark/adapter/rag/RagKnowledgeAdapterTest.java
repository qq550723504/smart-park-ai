package com.example.smartpark.adapter.rag;

import com.example.smartpark.demo.DemoFaultInjector;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagKnowledgeAdapterTest {

    @Test
    void searchesOnlyTheRequestedKnowledgeDomainIndex() {
        Map<KnowledgeDomain, VectorStore> stores = Map.of(
                KnowledgeDomain.CUSTOMER_SERVICE,
                SimpleVectorStore.builder(new KeywordEmbeddingModel()).build(),
                KnowledgeDomain.ALERT_OPERATIONS,
                SimpleVectorStore.builder(new KeywordEmbeddingModel()).build());
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores, List.of(
                document("KD-CUSTOMER-PARKING", "Customer parking", "customer parking guide", KnowledgeDomain.CUSTOMER_SERVICE),
                document("KD-ALERT-PARKING", "Alert parking", "alert parking runbook", KnowledgeDomain.ALERT_OPERATIONS)));

        assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking"))
                .extracting(KnowledgeDocument::id).containsExactly("KD-CUSTOMER-PARKING");
        assertThat(adapter.search(KnowledgeDomain.ALERT_OPERATIONS, "parking"))
                .extracting(KnowledgeDocument::id).containsExactly("KD-ALERT-PARKING");
    }

    @Test
    void ranksOnlyActiveDocumentsAndReturnsBoundedScores() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()), List.of(
                document("KD-PARKING-001", "Parking", "visitor parking entrance"),
                document("KD-ENERGY-001", "Energy", "energy meter baseline")));
        adapter.setActive("KD-ENERGY-001", false);

        List<KnowledgeMatch> matches = adapter.rankedSearch(KnowledgeDomain.CUSTOMER_SERVICE, "visitor parking");

        assertThat(matches).extracting(KnowledgeMatch::documentId).containsExactly("KD-PARKING-001");
        assertThat(matches).allMatch(match -> match.score() >= 0 && match.score() <= 1);
    }

    @Test
    void limitsTopKAndRejectsOversizedQueries() {
        List<KnowledgeDocument> documents = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> document("KD-TEST-" + index, "Test " + index, "parking " + index)).toList();
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()), documents);

        assertThat(adapter.rankedSearch(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).hasSize(RagKnowledgeAdapter.MAX_RESULTS);
        assertThatThrownBy(() -> adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesDocumentsBelowTheConfiguredSimilarityThreshold() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(
                stores(new ThresholdEmbeddingModel()),
                List.of(
                        document("KD-HIGH-001", "High", "high"),
                        document("KD-LOW-001", "Low", "low")),
                0.65);

        assertThat(adapter.rankedSearch(KnowledgeDomain.CUSTOMER_SERVICE, "query"))
                .extracting(KnowledgeMatch::documentId)
                .containsExactly("KD-HIGH-001");
    }

    @Test
    void excludesInactiveDocumentsBeforeApplyingTopK() {
        List<KnowledgeDocument> documents = new java.util.ArrayList<>();
        for (int index = 0; index < RagKnowledgeAdapter.MAX_RESULTS; index++) {
            documents.add(document("KD-INACTIVE-" + index, "Inactive " + index, "high"));
        }
        documents.add(document("KD-ACTIVE-001", "Active", "active"));
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(
                stores(new TopKEmbeddingModel()), documents);

        for (int index = 0; index < RagKnowledgeAdapter.MAX_RESULTS; index++) {
            adapter.setActive("KD-INACTIVE-" + index, false);
        }

        assertThat(adapter.rankedSearch(KnowledgeDomain.CUSTOMER_SERVICE, "query"))
                .extracting(KnowledgeMatch::documentId)
                .containsExactly("KD-ACTIVE-001");
    }

    @Test
    void failedEmbeddingDoesNotPublishPartialDocumentUpdate() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()),
                List.of(document("KD-SAFE-001", "Original", "parking")));

        assertThatThrownBy(() -> adapter.save(document("KD-SAFE-001", "Replacement", "FAIL")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(adapter.list().get(0).document().title()).isEqualTo("Original");
        assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).extracting(KnowledgeDocument::id).containsExactly("KD-SAFE-001");
    }

    @Test
    void movingDocumentToAnotherDomainRemovesItFromTheOldVectorIndex() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(stores(new KeywordEmbeddingModel()),
                List.of(document("KD-MOVE-001", "Customer parking", "customer parking")));

        adapter.save(document("KD-MOVE-001", "Alert energy", "alert energy", KnowledgeDomain.ALERT_OPERATIONS));

        assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).isEmpty();
        assertThat(adapter.search(KnowledgeDomain.ALERT_OPERATIONS, "energy"))
                .extracting(KnowledgeDocument::id)
                .containsExactly("KD-MOVE-001");
    }

    @Test
    void consumesInjectedKnowledgeSearchFaults() {
        DemoFaultInjector injector = new DemoFaultInjector();
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(
                stores(new KeywordEmbeddingModel()),
                List.of(document("KD-FAULT-001", "Parking", "parking")),
                RagKnowledgeAdapter.DEFAULT_MIN_SIMILARITY_SCORE,
                injector);
        injector.inject(new DemoFaultInjector.Fault(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH));

        assertThatThrownBy(() -> adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Injected demo fault at KNOWLEDGE_SEARCH");
    }

    private static KnowledgeDocument document(String id, String title, String content) {
        return document(id, title, content, KnowledgeDomain.CUSTOMER_SERVICE);
    }

    private static KnowledgeDocument document(String id, String title, String content, KnowledgeDomain domain) {
        return new KnowledgeDocument(id, domain, title, content, List.of("test"), Instant.EPOCH);
    }

    private static Map<KnowledgeDomain, VectorStore> stores(EmbeddingModel model) {
        return Map.of(
                KnowledgeDomain.CUSTOMER_SERVICE, SimpleVectorStore.builder(model).build(),
                KnowledgeDomain.ALERT_OPERATIONS, SimpleVectorStore.builder(model).build());
    }

    private static final class KeywordEmbeddingModel implements EmbeddingModel {
        @Override public float[] embed(Document document) { return embed(document.getText()); }
        @Override public float[] embed(String text) {
            if (text.contains("FAIL")) throw new IllegalStateException("embedding failed");
            String value = text.toLowerCase();
            return new float[] { value.contains("parking") ? 1 : 0, value.contains("energy") ? 1 : 0, 0.1f };
        }
        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
    }

    private static final class ThresholdEmbeddingModel implements EmbeddingModel {
        @Override public float[] embed(Document document) { return embed(document.getText()); }

        @Override public float[] embed(String text) {
            return switch (text) {
                case "query", "high" -> new float[] {1.0f, 0.0f};
                case "low" -> new float[] {0.4f, 0.9165151f};
                default -> new float[] {0.0f, 1.0f};
            };
        }

        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
    }

    private static final class TopKEmbeddingModel implements EmbeddingModel {
        @Override public float[] embed(Document document) { return embed(document.getText()); }

        @Override public float[] embed(String text) {
            return switch (text) {
                case "query", "high" -> new float[] {1.0f, 0.0f};
                case "active" -> new float[] {0.8f, 0.6f};
                default -> new float[] {0.0f, 1.0f};
            };
        }

        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
    }
}
