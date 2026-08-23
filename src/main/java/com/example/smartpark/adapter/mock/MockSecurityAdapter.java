package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityPort;

public final class MockSecurityAdapter implements SecurityPort {
    private final MockParkDataStore dataStore;

    public MockSecurityAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public SecurityEvent getEvent(String eventId) {
        return dataStore.getSecurityEvent(eventId);
    }
}
