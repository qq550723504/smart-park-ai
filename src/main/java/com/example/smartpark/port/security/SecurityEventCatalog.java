package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Source-aware aggregate over every registered ingestion path: the legacy
 * {@link SecurityEventReader} plus all {@link SecuritySourceAdapter} beans. The
 * alert workflow resolves a source-qualified reference here so it never reviews
 * another source's event when two adapters reuse the same source-local id, and
 * adapter-only deployments can still resolve adapter events.
 */
public final class SecurityEventCatalog implements SecurityEventResolver, SecurityEventReader {

    /** Prefers a concrete source over a source-less alias, then the freshest representation. */
    private static final Comparator<SecurityEvent> PREFERRED = Comparator
            .comparingInt((SecurityEvent event) -> SecurityEventIdentity.of(event).isSourceLess() ? 0 : 1)
            .thenComparing(SecurityEvent::receivedAt);

    private final SecurityEventReader reader;
    private final List<SecuritySourceAdapter> adapters;

    public SecurityEventCatalog(SecurityEventReader reader, List<SecuritySourceAdapter> adapters) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.adapters = List.copyOf(adapters == null ? List.of() : adapters);
    }

    /**
     * Builds the aggregate unless the reader already is one, so callers that inject
     * the shared {@code SecurityEventReader}/{@code SecurityPort} bean never wrap the
     * adapter aggregate a second time and double-read every adapter event.
     */
    public static SecurityEventReader aggregating(SecurityEventReader reader, List<SecuritySourceAdapter> adapters) {
        return reader instanceof SecurityEventCatalog ? reader : new SecurityEventCatalog(reader, adapters);
    }

    @Override
    public SecurityEvent getEvent(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return events().stream()
                .filter(event -> event.eventId().equals(eventId))
                .max(PREFERRED)
                .orElseThrow(() -> new NoSuchElementException("security event not found: " + eventId));
    }

    @Override
    public SecurityEvent getEvent(SecurityEventIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return events().stream()
                .filter(event -> SecurityEventIdentity.of(event).matches(identity))
                .max(PREFERRED)
                .orElseThrow(() -> new NoSuchElementException("security event not found: " + identity.eventId()));
    }

    /** Every event currently exposed by the legacy reader and the registered adapters. */
    public List<SecurityEvent> listEvents() {
        return events();
    }

    private List<SecurityEvent> events() {
        List<SecurityEvent> events = new ArrayList<>(reader.listEvents());
        adapters.forEach(adapter -> events.addAll(adapter.readEvents()));
        return events;
    }
}
