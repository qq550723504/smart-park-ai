package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.port.knowledge.KnowledgePort;

import java.util.List;

public final class MockKnowledgeAdapter implements KnowledgePort {
    private final MockParkDataStore dataStore;

    public MockKnowledgeAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public List<KnowledgeDocument> search(String query) {
        return dataStore.search(query);
    }
}
