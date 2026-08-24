package com.example.smartpark.adapter.mock;

import com.example.smartpark.demo.DemoFaultInjector;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import com.example.smartpark.port.knowledge.KnowledgeMatch;

import java.util.List;
import java.util.Locale;

public final class MockKnowledgeAdapter implements KnowledgeAdminPort {
    private final MockParkDataStore dataStore;
    private final DemoFaultInjector faultInjector;

    public MockKnowledgeAdapter(MockParkDataStore dataStore) {
        this(dataStore, new DemoFaultInjector());
    }

    public MockKnowledgeAdapter(MockParkDataStore dataStore, DemoFaultInjector faultInjector) {
        this.dataStore = dataStore;
        this.faultInjector = faultInjector;
    }

    @Override
    public List<KnowledgeAdminPort.ManagedDocument> list() {
        return dataStore.listKnowledge();
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument document) {
        return dataStore.saveKnowledge(document);
    }

    @Override
    public KnowledgeAdminPort.ManagedDocument setActive(String documentId, boolean active) {
        return dataStore.setKnowledgeActive(documentId, active);
    }

    @Override
    public List<KnowledgeDocument> search(String query) {
        faultInjector.failIfRequested(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH);
        return dataStore.search(query);
    }

    @Override
    public List<KnowledgeMatch> rankedSearch(String query) {
        return search(query).stream()
                .map(document -> new KnowledgeMatch(document.id(), document.title(), score(document, query)))
                .sorted(java.util.Comparator.comparingDouble(KnowledgeMatch::score).reversed()
                        .thenComparing(KnowledgeMatch::citationId))
                .toList();
    }

    private static double score(KnowledgeDocument document, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) return 0.0;
        if (document.tags().stream().anyMatch(tag -> tag.equalsIgnoreCase(normalizedQuery))) return 1.0;
        if (document.title().toLowerCase(Locale.ROOT).contains(normalizedQuery)) return 0.85;
        if (document.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(normalizedQuery))) return 0.75;
        if (document.content().toLowerCase(Locale.ROOT).contains(normalizedQuery)) return 0.70;
        return 0.0;
    }
}
