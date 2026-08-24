package com.example.smartpark.adapter.mock;

import com.example.smartpark.demo.DemoFaultInjector;
import com.example.smartpark.model.common.KnowledgeDocument;
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
    public List<KnowledgeDocument> search(String query) {
        faultInjector.failIfRequested(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH);
        return dataStore.search(query);
    }
}
