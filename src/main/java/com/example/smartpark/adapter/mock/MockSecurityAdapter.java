package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceRef;
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
        return withDeclaredSource(dataStore.getSecurityEvent(eventId));
    }

    @Override
    public List<SecurityEvent> listEvents() {
        return dataStore.listSecurityEvents().stream().map(MockSecurityAdapter::withDeclaredSource).toList();
    }

    @Override
    public SecuritySourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<SecurityEvent> readEvents() {
        return listEvents();
    }

    /**
     * The demo data store seeds events through the compatibility constructor, which
     * leaves the source {@code UNKNOWN}. This adapter declares an access-control
     * source, so emitted events carry that identity instead of being reported as
     * source-less and de-duplicated as a legacy alias.
     */
    private static SecurityEvent withDeclaredSource(SecurityEvent event) {
        SecuritySourceRef declared = new SecuritySourceRef(DESCRIPTOR.sourceType(), DESCRIPTOR.sourceId());
        if (declared.equals(event.source())) return event;
        return new SecurityEvent(event.eventId(), event.parkId(), event.buildingId(), event.eventType(),
                event.rawEventType(), declared, event.location(), event.observedAt(), event.receivedAt(),
                event.severity(), event.confidence(), event.privacy(), event.disposition(), event.ingestedBy(),
                event.ingestVersion(), event.evidenceSummary());
    }
}
