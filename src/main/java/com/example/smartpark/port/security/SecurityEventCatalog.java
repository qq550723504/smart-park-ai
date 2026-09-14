package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

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
        return select(events().stream()
                .filter(event -> event.eventId().equals(eventId))
                .toList(), eventId);
    }

    @Override
    public SecurityEvent getEvent(SecurityEventIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        List<SecurityEvent> events = events();
        if (identity.isSourceLess()) {
            return select(events.stream()
                    .filter(event -> SecurityEventIdentity.of(event).matches(identity))
                    .toList(), identity.eventId());
        }
        // A source-qualified reference must resolve to that exact source. Falling back to a
        // source-less legacy alias — or to a different source that merely reused the id —
        // would ground the caller in an event never attributed to the requested source.
        return events.stream()
                .filter(event -> SecurityEventIdentity.of(event).equals(identity))
                .max(PREFERRED)
                .orElseThrow(() -> new NoSuchElementException(
                        "security event not found for source: " + identity.reference()));
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

    /**
     * Returns the single logical event the candidates represent. A bare id (or a
     * source-less reference) can match copies of the same id belonging to several
     * concrete sources, parks, or buildings; returning the freshest would silently
     * ground the caller in the wrong event, so an ambiguous lookup fails loudly and
     * asks for a source-qualified reference instead. Copies of one logical event
     * (a concrete source plus its source-less aliases, or the same source read
     * twice) still collapse.
     */
    private static SecurityEvent select(List<SecurityEvent> candidates, String eventId) {
        if (candidates.isEmpty()) {
            throw new NoSuchElementException("security event not found: " + eventId);
        }
        if (distinctSources(candidates) > 1) {
            throw new IllegalArgumentException("ambiguous security event id: " + eventId
                    + "; use a source-qualified reference");
        }
        return candidates.stream().max(PREFERRED).orElseThrow();
    }

    /**
     * Counts the logical events the candidates belong to: one per concrete source,
     * plus one per location that only has source-less aliases (which cannot be
     * attributed to a concrete source).
     */
    private static int distinctSources(List<SecurityEvent> events) {
        Set<SecurityEventIdentity> concrete = new LinkedHashSet<>();
        Set<String> concreteLocations = new LinkedHashSet<>();
        for (SecurityEvent event : events) {
            SecurityEventIdentity identity = SecurityEventIdentity.of(event);
            if (!identity.isSourceLess()) {
                concrete.add(identity);
                concreteLocations.add(location(identity));
            }
        }
        Set<String> orphanLocations = new LinkedHashSet<>();
        for (SecurityEvent event : events) {
            SecurityEventIdentity identity = SecurityEventIdentity.of(event);
            if (identity.isSourceLess() && !concreteLocations.contains(location(identity))) {
                orphanLocations.add(location(identity));
            }
        }
        return concrete.size() + orphanLocations.size();
    }

    private static String location(SecurityEventIdentity identity) {
        return identity.parkId() + '\u0000' + identity.buildingId();
    }
}
