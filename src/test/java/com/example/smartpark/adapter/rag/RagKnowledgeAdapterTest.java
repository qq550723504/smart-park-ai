package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeMatch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagKnowledgeAdapterTest {

    @Test
    void ranksOnlyActiveDocumentsAndReturnsBoundedScores() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(SimpleVectorStore.builder(new KeywordEmbeddingModel()).build(), List.of(
                document("KD-PARKING-001", "Parking", "visitor parking entrance"),
                document("KD-ENERGY-001", "Energy", "energy meter baseline")));
        adapter.setActive("KD-ENERGY-001", false);

        List<KnowledgeMatch> matches = adapter.rankedSearch("visitor parking");

        assertThat(matches).extracting(KnowledgeMatch::documentId).containsExactly("KD-PARKING-001");
        assertThat(matches).allMatch(match -> match.score() >= 0 && match.score() <= 1);
    }

    @Test
    void limitsTopKAndRejectsOversizedQueries() {
        List<KnowledgeDocument> documents = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> document("KD-TEST-" + index, "Test " + index, "parking " + index)).toList();
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(SimpleVectorStore.builder(new KeywordEmbeddingModel()).build(), documents);

        assertThat(adapter.rankedSearch("parking")).hasSize(RagKnowledgeAdapter.MAX_RESULTS);
        assertThatThrownBy(() -> adapter.search("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludesDocumentsBelowTheConfiguredSimilarityThreshold() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(
                SimpleVectorStore.builder(new ThresholdEmbeddingModel()).build(),
                List.of(
                        document("KD-HIGH-001", "High", "high"),
                        document("KD-LOW-001", "Low", "low")),
                0.65);

        assertThat(adapter.rankedSearch("query"))
                .extracting(KnowledgeMatch::documentId)
                .containsExactly("KD-HIGH-001");
    }

    @Test
    void failedEmbeddingDoesNotPublishPartialDocumentUpdate() {
        RagKnowledgeAdapter adapter = new RagKnowledgeAdapter(SimpleVectorStore.builder(new KeywordEmbeddingModel()).build(),
                List.of(document("KD-SAFE-001", "Original", "parking")));

        assertThatThrownBy(() -> adapter.save(document("KD-SAFE-001", "Replacement", "FAIL")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(adapter.list().get(0).document().title()).isEqualTo("Original");
    }

    private static KnowledgeDocument document(String id, String title, String content) {
        return new KnowledgeDocument(id, title, content, List.of("test"), Instant.EPOCH);
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
}
