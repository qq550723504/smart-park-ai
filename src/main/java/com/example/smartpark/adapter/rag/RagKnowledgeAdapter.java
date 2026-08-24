package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Adapter from the application knowledge ports to Spring AI's VectorStore abstraction. */
public final class RagKnowledgeAdapter implements KnowledgeAdminPort {
    public static final int MAX_RESULTS = 5;
    public static final int MAX_QUERY_LENGTH = 500;
    public static final int MAX_DOCUMENT_LENGTH = 2_000;
    public static final double DEFAULT_MIN_SIMILARITY_SCORE = 0.65;

    private final VectorStore vectorStore;
    private final double minSimilarityScore;
    private final Map<String, ManagedDocument> metadata = new ConcurrentHashMap<>();

    public RagKnowledgeAdapter(VectorStore vectorStore, Collection<KnowledgeDocument> seedDocuments) {
        this(vectorStore, seedDocuments, DEFAULT_MIN_SIMILARITY_SCORE);
    }

    public RagKnowledgeAdapter(
            VectorStore vectorStore,
            Collection<KnowledgeDocument> seedDocuments,
            double minSimilarityScore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        if (!Double.isFinite(minSimilarityScore) || minSimilarityScore < 0.0 || minSimilarityScore > 1.0) {
            throw new IllegalArgumentException("minSimilarityScore must be between 0 and 1");
        }
        this.minSimilarityScore = minSimilarityScore;
        Objects.requireNonNull(seedDocuments, "seedDocuments").forEach(this::save);
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
        return rankedSearch(domain, query).stream().map(KnowledgeMatch::document).toList();
    }

    @Override
    public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
        String normalized = validateQuery(query);
        if (normalized.isBlank()) return List.of();
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(normalized)
                        .topK(MAX_RESULTS)
                        .similarityThreshold(minSimilarityScore)
                        .build()).stream()
                .map(this::toMatch)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ManagedDocument> list() {
        return metadata.values().stream()
                .sorted(Comparator.comparing(item -> item.document().id()))
                .toList();
    }

    @Override
    public synchronized KnowledgeDocument save(KnowledgeDocument document) {
        validateDocument(document);
        Document vectorDocument = toVectorDocument(document);
        vectorStore.add(List.of(vectorDocument));
        metadata.put(document.id(), new ManagedDocument(document, true));
        return document;
    }

    @Override
    public synchronized ManagedDocument setActive(String documentId, boolean active) {
        Objects.requireNonNull(documentId, "documentId");
        ManagedDocument current = metadata.get(documentId);
        if (current == null) throw new IllegalArgumentException("Unknown knowledge document: " + documentId);
        if (current.active() == active) return current;
        if (active) {
            vectorStore.add(List.of(toVectorDocument(current.document())));
        } else {
            vectorStore.delete(List.of(documentId));
        }
        ManagedDocument updated = new ManagedDocument(current.document(), active);
        metadata.put(documentId, updated);
        return updated;
    }

    private KnowledgeMatch toMatch(Document document) {
        ManagedDocument managed = metadata.get(document.getId());
        if (managed == null || !managed.active()) return null;
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new KnowledgeMatch(managed.document(), Math.max(0.0, Math.min(1.0, score)));
    }

    private static Document toVectorDocument(KnowledgeDocument document) {
        return Document.builder()
                .id(document.id())
                .text(document.content())
                .metadata(Map.of("title", document.title(), "tags", String.join(",", document.tags()),
                        "updatedAt", document.updatedAt().toString()))
                .build();
    }

    private static String validateQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException(
                "knowledge query must not exceed " + MAX_QUERY_LENGTH + " characters");
        return value;
    }

    private static void validateDocument(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document");
        if (document.content().length() > MAX_DOCUMENT_LENGTH) throw new IllegalArgumentException(
                "knowledge document content must not exceed " + MAX_DOCUMENT_LENGTH + " characters");
    }
}
