package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.port.security.SecuritySourceDescriptor;

import java.util.List;
import java.util.Set;

public final class MockSecurityAdapter implements SecurityEventReader, SecuritySourceAdapter {
    private static final SecuritySourceDescriptor DESCRIPTOR = new SecuritySourceDescriptor(
            "mock-access-control-feed",
            SecuritySourceType.ACCESS_CONTROL,
            Set.of(SecurityEventType.ACCESS_ANOMALY),
            false);

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

    @Override
    public SecuritySourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<SecurityEvent> readEvents() {
        return listEvents();
    }
}
