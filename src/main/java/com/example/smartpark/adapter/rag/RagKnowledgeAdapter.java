package com.example.smartpark.adapter.rag;

import com.example.smartpark.demo.DemoFaultInjector;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Adapter from the application knowledge ports to Spring AI's VectorStore abstraction. */
public final class RagKnowledgeAdapter implements KnowledgeAdminPort {
    public static final int MAX_RESULTS = 5;
    public static final int MAX_QUERY_LENGTH = 500;
    public static final int MAX_DOCUMENT_LENGTH = KnowledgeDocument.MAX_CONTENT_LENGTH;
    public static final int MAX_EMBEDDED_TEXT_LENGTH = 3_000;
    public static final double DEFAULT_MIN_SIMILARITY_SCORE = 0.65;

    private final Map<KnowledgeDomain, VectorStore> vectorStores;
    private final double minSimilarityScore;
    private final DemoFaultInjector faultInjector;
    private final Map<String, ManagedDocument> metadata = new ConcurrentHashMap<>();
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    public RagKnowledgeAdapter(
            Map<KnowledgeDomain, VectorStore> vectorStores,
            Collection<KnowledgeDocument> seedDocuments,
            double minSimilarityScore) {
        this(vectorStores, seedDocuments, minSimilarityScore, new DemoFaultInjector());
    }

    public RagKnowledgeAdapter(
            Map<KnowledgeDomain, VectorStore> vectorStores,
            Collection<KnowledgeDocument> seedDocuments,
            double minSimilarityScore,
            DemoFaultInjector faultInjector) {
        this.vectorStores = validateVectorStores(vectorStores);
        if (!Double.isFinite(minSimilarityScore) || minSimilarityScore < 0.0 || minSimilarityScore > 1.0) {
            throw new IllegalArgumentException("minSimilarityScore must be between 0 and 1");
        }
        this.minSimilarityScore = minSimilarityScore;
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
        List<KnowledgeDocument> seeds = List.copyOf(Objects.requireNonNull(seedDocuments, "seedDocuments"));
        Set<String> seedIds = new HashSet<>();
        for (KnowledgeDocument seed : seeds) {
            if (!seedIds.add(Objects.requireNonNull(seed, "seed document").id())) {
                throw new IllegalArgumentException("duplicate knowledge document id in seed documents: " + seed.id());
            }
        }
        seeds.forEach(this::save);
    }

    @Override
    public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
        return rankedSearch(domain, query).stream().map(KnowledgeMatch::document).toList();
    }

    public RagKnowledgeAdapter(
            Map<KnowledgeDomain, VectorStore> vectorStores,
            Collection<KnowledgeDocument> seedDocuments) {
        this(vectorStores, seedDocuments, DEFAULT_MIN_SIMILARITY_SCORE);
    }

    @Override
    public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
        indexLock.readLock().lock();
        try {
            String normalized = validateQuery(query);
            if (normalized.isBlank()) return List.of();
            faultInjector.failIfRequested(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH);
            return vectorStore(domain).similaritySearch(SearchRequest.builder()
                            .query(normalized)
                            .topK(MAX_RESULTS)
                            .similarityThreshold(minSimilarityScore)
                            .build()).stream()
                    .map(document -> toMatch(domain, document))
                    .filter(Objects::nonNull)
                    .toList();
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public List<ManagedDocument> list() {
        indexLock.readLock().lock();
        try {
            return metadata.values().stream()
                    .sorted(Comparator.comparing(item -> item.document().id()))
                    .toList();
        } finally {
            indexLock.readLock().unlock();
        }
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument document) {
        indexLock.writeLock().lock();
        try {
            validateDocument(document);
            Document vectorDocument = toVectorDocument(document);
            vectorStore(document.domain()).add(List.of(vectorDocument));
            ManagedDocument previous = metadata.get(document.id());
            if (previous != null && previous.document().domain() != document.domain()) {
                try {
                    vectorStore(previous.document().domain()).delete(List.of(document.id()));
                } catch (RuntimeException failure) {
                    vectorStore(document.domain()).delete(List.of(document.id()));
                    throw failure;
                }
            }
            metadata.put(document.id(), new ManagedDocument(document, true));
            return document;
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    @Override
    public ManagedDocument setActive(String documentId, boolean active) {
        indexLock.writeLock().lock();
        try {
            Objects.requireNonNull(documentId, "documentId");
            ManagedDocument current = metadata.get(documentId);
            if (current == null) throw new IllegalArgumentException("Unknown knowledge document: " + documentId);
            if (current.active() == active) return current;
            if (active) {
                vectorStore(current.document().domain()).add(List.of(toVectorDocument(current.document())));
            } else {
                vectorStore(current.document().domain()).delete(List.of(documentId));
            }
            ManagedDocument updated = new ManagedDocument(current.document(), active);
            metadata.put(documentId, updated);
            return updated;
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private KnowledgeMatch toMatch(KnowledgeDomain domain, Document document) {
        ManagedDocument managed = metadata.get(document.getId());
        if (managed == null || !managed.active() || managed.document().domain() != domain) return null;
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new KnowledgeMatch(managed.document(), Math.max(0.0, Math.min(1.0, score)));
    }

    private static Document toVectorDocument(KnowledgeDocument document) {
        String embeddedText = "Title: " + document.title() + "\nTags: " + String.join(", ", document.tags())
                + "\nContent: " + document.content();
        if (embeddedText.length() > MAX_EMBEDDED_TEXT_LENGTH) {
            throw new IllegalArgumentException("knowledge embedding input must not exceed " + MAX_EMBEDDED_TEXT_LENGTH + " characters");
        }
        return Document.builder()
                .id(document.id())
                .text(embeddedText)
                .metadata(Map.of("title", document.title(), "tags", String.join(",", document.tags()),
                        "updatedAt", document.updatedAt().toString()))
                .build();
    }

    private VectorStore vectorStore(KnowledgeDomain domain) {
        return vectorStores.get(Objects.requireNonNull(domain, "domain"));
    }

    private static Map<KnowledgeDomain, VectorStore> validateVectorStores(
            Map<KnowledgeDomain, VectorStore> vectorStores) {
        Objects.requireNonNull(vectorStores, "vectorStores");
        EnumSet<KnowledgeDomain> missing = EnumSet.allOf(KnowledgeDomain.class);
        missing.removeAll(vectorStores.keySet());
        if (!missing.isEmpty()) throw new IllegalArgumentException("vectorStores missing domains: " + missing);
        EnumMap<KnowledgeDomain, VectorStore> copy = new EnumMap<>(KnowledgeDomain.class);
        for (KnowledgeDomain domain : KnowledgeDomain.values()) {
            copy.put(domain, Objects.requireNonNull(vectorStores.get(domain), domain.name()));
        }
        return Map.copyOf(copy);
    }

    private static String validateQuery(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException(
                "knowledge query must not exceed " + MAX_QUERY_LENGTH + " characters");
        return value;
    }

    private static void validateDocument(KnowledgeDocument document) {
        Objects.requireNonNull(document, "document");
    }
}
