package com.example.smartpark.adapter.mock;

import com.example.smartpark.demo.DemoFaultInjector;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;

import java.util.List;

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
    public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
        faultInjector.failIfRequested(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH);
        return dataStore.search(domain, query);
    }

    @Override
    public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
        return search(domain, query).stream()
                .map(document -> new KnowledgeMatch(document, stableScore(document, query)))
                .toList();
    }

    private static double stableScore(KnowledgeDocument document, String query) {
        if (query == null || query.isBlank()) return 0.0;
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        boolean tagMatch = document.tags().stream()
                .map(tag -> tag.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(tag -> tag.contains(normalized));
        return tagMatch ? 0.95 : 0.85;
    }
}
