package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
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
    public static SecurityEventCatalog aggregating(SecurityEventReader reader, List<SecuritySourceAdapter> adapters) {
        return reader instanceof SecurityEventCatalog catalog ? catalog : new SecurityEventCatalog(reader, adapters);
    }

    @Override
    public SecurityEvent getEvent(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        List<SecurityEvent> matches = events().stream()
                .filter(event -> event.eventId().equals(eventId))
                .toList();
        // A get-only legacy port cannot enumerate its events, so consult its direct
        // lookup when the aggregated stream has no match. An empty reader still fails here.
        if (matches.isEmpty() && reader instanceof SecurityPortReader) return reader.getEvent(eventId);
        return select(matches, eventId);
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
        List<SecurityEvent> matches = events.stream()
                .filter(event -> SecurityEventIdentity.of(event).equals(identity))
                .toList();
        if (matches.isEmpty()) {
            throw new NoSuchElementException("security event not found for source: " + identity.reference());
        }
        return preferred(matches);
    }

    /**
     * Resolves a reference emitted by {@link SecurityEventIdentity#reference()}. A
     * source-qualified token matches only events of that exact source, so it recovers a
     * colliding bare id that {@link #getEvent(String)} rejects as ambiguous; a legacy bare
     * token aliases any source of the same event id, like the bare-id lookup. A token that
     * omits the location still resolves when the source reuse is unique, and is rejected as
     * ambiguous only when the remaining location candidates disagree.
     */
    @Override
    public SecurityEvent getEventByReference(String reference) {
        Objects.requireNonNull(reference, "reference");
        if (!SecurityEventIdentity.isReference(reference)) {
            throw new SecurityEventLookupException("not a security event reference: " + reference);
        }
        if (!SecurityEventIdentity.isQualifiedReference(reference)) {
            return getEvent(SecurityEventIdentity.eventIdOfReference(reference));
        }
        SecurityEventIdentity.QualifiedReference qualified =
                SecurityEventIdentity.parseQualifiedReference(reference);
        if (qualified == null) {
            // The token decodes but names an unknown or misspelled source type: reject it
            // rather than downgrade to a bare-id lookup that could ground another source.
            throw new NoSuchElementException("security event not found for reference: " + reference);
        }
        List<SecurityEvent> matches = events().stream()
                .filter(event -> qualified.matches(SecurityEventIdentity.of(event)))
                .toList();
        if (matches.isEmpty()) {
            throw new NoSuchElementException("security event not found for reference: " + reference);
        }
        if (!qualified.hasLocation() && distinctLocations(matches) > 1) {
            throw new SecurityEventLookupException("ambiguous security event reference: " + reference);
        }
        return preferred(matches);
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
            throw new SecurityEventLookupException("ambiguous security event id: " + eventId
                    + "; use a source-qualified reference");
        }
        return preferred(candidates);
    }

    /**
     * Picks the freshest preferred copy of one logical event and attaches the disposition
     * the copies jointly agree on. Copies can disagree when a source corrects a decision
     * without advancing {@code receivedAt}; selecting only by source concreteness and
     * receipt freshness would then hand a stale or {@code UNREVIEWED} disposition to
     * callers such as {@code SecurityQueryTool}.
     */
    private static SecurityEvent preferred(List<SecurityEvent> candidates) {
        SecurityEvent preferred = candidates.stream().max(PREFERRED).orElseThrow();
        SecurityDispositionRecord reconciled = reconcileDisposition(candidates);
        return reconciled == null || reconciled.equals(preferred.disposition())
                ? preferred
                : preferred.withDisposition(reconciled);
    }

    /**
     * Reconciles the disposition records of one logical event's copies. A human review
     * stays authoritative; otherwise the latest decision wins, matching the incident
     * service so query consumers see the same disposition. Returns {@code null} when no
     * copy carries a decision, leaving the preferred copy untouched.
     */
    private static SecurityDispositionRecord reconcileDisposition(List<SecurityEvent> candidates) {
        SecurityDispositionRecord humanReview = candidates.stream()
                .map(SecurityEvent::disposition)
                .filter(record -> record.source() == SecurityDispositionSource.HUMAN_REVIEW)
                .min(Comparator.comparing(SecurityDispositionRecord::decidedAt))
                .orElse(null);
        if (humanReview != null) return humanReview;
        return candidates.stream()
                .map(SecurityEvent::disposition)
                .filter(record -> record.disposition() != SecurityDisposition.UNREVIEWED)
                .max(Comparator.comparing(SecurityDispositionRecord::decidedAt))
                .orElse(null);
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

    private static int distinctLocations(List<SecurityEvent> events) {
        Set<String> locations = new LinkedHashSet<>();
        events.forEach(event -> locations.add(location(SecurityEventIdentity.of(event))));
        return locations.size();
    }
}
