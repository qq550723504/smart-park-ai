package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityPort;

import java.util.List;

public final class MockSecurityAdapter implements SecurityPort {
    private final MockParkDataStore dataStore;

    public MockSecurityAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public SecurityEvent getEvent(String eventId) {
        return dataStore.getSecurityEvent(eventId);
    }

    @Override
    public List<SecurityEvent> listEvents() {
        return dataStore.listSecurityEvents();
    }
}
