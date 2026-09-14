package com.example.smartpark.securityincident;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecuritySourceAdapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

public final class SecurityIncidentService {
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(15);

    private final SecurityEventReader security;
    private final AlertPort alerts;
    private final SecurityIncidentStore store;
    private final SecurityIncidentHandoffPort handoffs;
    private final List<SecuritySourceAdapter> sourceAdapters;
    private final Clock clock;

    public SecurityIncidentService(SecurityEventReader security, AlertPort alerts, SecurityIncidentStore store,
                                   SecurityIncidentHandoffPort handoffs, Clock clock) {
        this(security, alerts, store, handoffs, clock, List.of());
    }

    public SecurityIncidentService(SecurityEventReader security, AlertPort alerts, SecurityIncidentStore store,
                                   SecurityIncidentHandoffPort handoffs, Clock clock,
                                   List<SecuritySourceAdapter> sourceAdapters) {
        this.security = Objects.requireNonNull(security, "security");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.store = Objects.requireNonNull(store, "store");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
        this.sourceAdapters = List.copyOf(sourceAdapters == null ? List.of() : sourceAdapters);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized SecurityIncidentPage list(SecurityIncidentQuery query) {
        Objects.requireNonNull(query, "query");
        List<SecurityIncident> filtered = currentIncidents(query.status());
        return new SecurityIncidentPage(filtered.stream().skip(query.offset()).limit(query.limit()).toList(), filtered.size());
    }

    /** Returns matching incidents from one correlated snapshot without page truncation. */
    public synchronized List<SecurityIncident> findMatching(List<String> buildingIds, String alertId) {
        List<String> safeBuildingIds = List.copyOf(buildingIds == null ? List.of() : buildingIds);
        String safeAlertId = alertId == null || alertId.isBlank() ? null : alertId.trim();
        return currentIncidents(null).stream()
                .filter(incident -> safeBuildingIds.contains(incident.buildingId())
                        || (safeAlertId != null && incident.alertIds().contains(safeAlertId)))
                .toList();
    }

    public synchronized SecurityIncident get(String incidentId) {
        return currentIncidents(null).stream()
                .filter(incident -> incident.incidentId().equals(incidentId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("security incident not found"));
    }

    private List<SecurityIncident> currentIncidents(SecurityIncidentStatus status) {
        List<SecurityIncident> correlated = correlate();
        List<SecurityIncident> merged = restoreStates(correlated);
        Set<String> refreshedHandoffs = new HashSet<>();
        merged.forEach(incident -> {
            store.save(incident);
            if (incident.status() == SecurityIncidentStatus.HANDOFF
                    && incident.handoffWorkItemId() != null
                    && refreshedHandoffs.add(incident.handoffWorkItemId())) {
                handoffs.refresh(incident, clock.instant());
            }
        });
        Set<String> retainedIncidentIds = store.findAll().stream().map(SecurityIncident::incidentId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> retainedHandoffWorkItemIds = handoffs.list().stream()
                .map(SecurityIncidentHandoff::workItemId)
                .collect(java.util.stream.Collectors.toSet());
        List<SecurityIncident> filtered = merged.stream()
                .filter(incident -> incident.status() == SecurityIncidentStatus.HANDOFF
                        ? incident.handoffWorkItemId() != null
                        && retainedHandoffWorkItemIds.contains(incident.handoffWorkItemId())
                        : retainedIncidentIds.contains(incident.incidentId()))
                .filter(incident -> status == null || incident.status() == status)
                .sorted(Comparator.comparing(SecurityIncident::riskLevel, Comparator.comparingInt(SecurityIncidentService::riskRank).reversed())
                        .thenComparing(SecurityIncident::lastOccurredAt, Comparator.reverseOrder())
                        .thenComparing(SecurityIncident::incidentId))
                .toList();
        return filtered;
    }

    public synchronized SecurityIncident review(String incidentId) {
        return review(incidentId, SecurityDisposition.CONFIRMED_INCIDENT, "APPROVER");
    }

    public synchronized SecurityIncident review(String incidentId, SecurityDisposition disposition, String actor) {
        return applyReview(incidentId, disposition, actor).incident();
    }

    /**
     * Applies a human review and reports whether this call actually persisted a
     * new decision. A stale or concurrent request against an already-reviewed
     * incident returns the stored incident with {@code applied == false} so the
     * caller can audit the idempotent no-op and the disposition that was kept.
     */
    public synchronized ReviewOutcome applyReview(String incidentId, SecurityDisposition disposition, String actor) {
        Objects.requireNonNull(disposition, "disposition");
        SecurityIncident current = get(incidentId);
        if (current.status() != SecurityIncidentStatus.OPEN) return new ReviewOutcome(current, false);
        if (disposition == SecurityDisposition.UNREVIEWED) {
            throw new IllegalArgumentException("review requires a decided disposition");
        }
        Instant now = clock.instant();
        SecurityDispositionRecord record = new SecurityDispositionRecord(disposition,
                SecurityDispositionSource.HUMAN_REVIEW, actor, null, null, evidenceRefFor(incidentId), now);
        SecurityIncident reviewed = current.review(record, now);
        store.save(reviewed);
        return new ReviewOutcome(reviewed, true);
    }

    private static String evidenceRefFor(String incidentId) {
        return "human-review:" + incidentId;
    }

    public synchronized SecurityIncident handoff(String incidentId) {
        SecurityIncident current = get(incidentId);
        if (current.handoffWorkItemId() != null) {
            handoffs.refresh(current, clock.instant());
            return current;
        }
        if (current.status() != SecurityIncidentStatus.REVIEWED) {
            throw new IllegalStateException("security incident must be reviewed before handoff");
        }
        SecurityIncidentHandoff handoff = handoffs.createOrGet(current, clock.instant());
        SecurityIncident result = current.handoff(handoff.workItemId(), clock.instant());
        store.save(result);
        return result;
    }

    private List<SecurityIncident> correlate() {
        List<SecurityEvent> ingested = new ArrayList<>(security.listEvents());
        sourceAdapters.forEach(adapter -> ingested.addAll(adapter.readEvents()));
        Map<CorrelationKey, List<SecurityEvent>> buckets = new LinkedHashMap<>();
        deduplicate(ingested).stream()
                .sorted(Comparator.comparing(SecurityEvent::occurredAt).thenComparing(SecurityEvent::eventId))
                .forEach(event -> buckets.computeIfAbsent(bucketKey(event), ignored -> new ArrayList<>()).add(event));
        Map<AlertReferenceKey, List<Alert>> alertsByReference = alertsByReference();
        List<SecurityIncident> incidents = new ArrayList<>();
        buckets.values().forEach(events -> splitBucket(events, alertsByReference, incidents));
        return incidents;
    }

    /**
     * Folds a logically identical event seen through multiple ingestion paths.
     * Concrete-source copies are grouped by their source-aware identity, so two
     * adapters that reuse the same source-local {@code eventId} stay distinct. A
     * legacy source-less copy is an alias of the concrete copy of the same event;
     * when several sources reuse the id it is folded into the best match — same
     * event type first, then nearest occurrence time — rather than whichever
     * adapter happened to be read first, so an unrelated source can neither swallow
     * the legacy representation nor be swallowed by it.
     */
    private static List<SecurityEvent> deduplicate(List<SecurityEvent> ingested) {
        Map<SecurityEventIdentity, SecurityEvent> deduplicated = new LinkedHashMap<>();
        List<SecurityEvent> aliases = new ArrayList<>();
        for (SecurityEvent event : ingested) {
            SecurityEventIdentity identity = SecurityEventIdentity.of(event);
            if (identity.isSourceLess()) {
                aliases.add(event);
            } else {
                deduplicated.merge(identity, event, SecurityIncidentService::authoritativeEvent);
            }
        }
        for (SecurityEvent alias : aliases) {
            SecurityEventIdentity identity = SecurityEventIdentity.of(alias);
            Map.Entry<SecurityEventIdentity, SecurityEvent> match = deduplicated.entrySet().stream()
                    .filter(entry -> entry.getKey().matches(identity))
                    .min(aliasPreference(alias))
                    .orElse(null);
            if (match != null) {
                deduplicated.put(match.getKey(), authoritativeEvent(match.getValue(), alias));
            } else {
                deduplicated.merge(identity, alias, SecurityIncidentService::authoritativeEvent);
            }
        }
        return List.copyOf(deduplicated.values());
    }

    /**
     * Ranks the concrete copies a source-less alias may fold into: a concrete source
     * is preferred over another alias, then the same correlation type (which, for an
     * unsupported vendor code, includes the raw type so two distinct UNKNOWN codes do
     * not tie), then the nearest occurrence time, with the identity material as a
     * stable final tie-breaker.
     */
    private static Comparator<Map.Entry<SecurityEventIdentity, SecurityEvent>> aliasPreference(
            SecurityEvent alias) {
        return Comparator
                .comparingInt((Map.Entry<SecurityEventIdentity, SecurityEvent> entry) ->
                        entry.getKey().isSourceLess() ? 1 : 0)
                .thenComparingInt(entry ->
                        correlationType(entry.getValue()).equals(correlationType(alias)) ? 0 : 1)
                .thenComparingLong(entry -> Math.abs(
                        Duration.between(entry.getValue().occurredAt(), alias.occurredAt()).toMillis()))
                .thenComparing(entry -> entry.getKey().material());
    }

    /**
     * Picks the representation of a logically identical event that arrives through
     * multiple ingestion paths (legacy reader and/or registered adapters). The
     * reconciled decision is always preserved, but it is attached to the enriched
     * representation rather than forcing the copy that happens to carry it: a
     * concrete adapter view keeps its source, severity, confidence and ingest
     * metadata even when a source-less reader copy is the one that decided. When
     * neither path is decided, the freshest representation by {@code receivedAt}
     * wins (favouring a concrete adapter view on a tie). The reconciliation rule is
     * the same one used by the restore paths: a stored human review stays
     * authoritative, otherwise the newest decision wins.
     */
    private static SecurityEvent authoritativeEvent(SecurityEvent left, SecurityEvent right) {
        SecurityDispositionRecord reconciled = reconcileDisposition(right.disposition(), List.of(left.disposition()));
        if (reconciled.disposition() == SecurityDisposition.UNREVIEWED) return fresherEvent(left, right);
        return preferredRepresentation(left, right).withDisposition(reconciled);
    }

    /**
     * Chooses which copy's metadata survives a merge. A concrete adapter view is
     * richer than a legacy view, and an event classified with a severity,
     * confidence and adapter ingest metadata is richer than a bare reader copy, so
     * richness wins even when the richer copy's receipt time is older; copies of
     * equal richness fall back to freshness.
     */
    private static SecurityEvent preferredRepresentation(SecurityEvent left, SecurityEvent right) {
        int richness = Integer.compare(enrichmentScore(right), enrichmentScore(left));
        if (richness != 0) return richness > 0 ? right : left;
        return fresherEvent(left, right);
    }

    private static int enrichmentScore(SecurityEvent event) {
        int score = 0;
        if (event.source().sourceType() != SecuritySourceType.UNKNOWN) score += 8;
        if (event.severity() != SecurityEventSeverity.UNKNOWN) score += 4;
        if (event.confidence() != null) score += 2;
        if (!"unspecified".equals(event.ingestedBy()) || event.ingestVersion() != null) score += 1;
        return score;
    }

    /** Freshest representation by {@code receivedAt}, preferring the concrete adapter view on a tie. */
    private static SecurityEvent fresherEvent(SecurityEvent left, SecurityEvent right) {
        int freshness = right.receivedAt().compareTo(left.receivedAt());
        if (freshness != 0) return freshness > 0 ? right : left;
        // Timestamps tie when an adapter enriches a legacy event without
        // advancing receivedAt; prefer the concrete/enriched representation.
        return sourceConcreteness(right) > sourceConcreteness(left) ? right : left;
    }

    private static int sourceConcreteness(SecurityEvent event) {
        return event.source().sourceType() == SecuritySourceType.UNKNOWN ? 0 : 1;
    }

    private void splitBucket(List<SecurityEvent> events, Map<AlertReferenceKey, List<Alert>> alertsByReference,
                             List<SecurityIncident> target) {
        List<SecurityEvent> current = new ArrayList<>();
        for (SecurityEvent event : events) {
            if (!current.isEmpty() && Duration.between(current.get(current.size() - 1).occurredAt(), event.occurredAt()).compareTo(GROUPING_WINDOW) > 0) {
                target.add(build(current, alertsByReference));
                current.clear();
            }
            current.add(event);
        }
        if (!current.isEmpty()) target.add(build(current, alertsByReference));
    }

    private SecurityIncident build(List<SecurityEvent> events, Map<AlertReferenceKey, List<Alert>> alertsByReference) {
        SecurityEvent first = events.get(0);
        List<Alert> linkedAlerts = events.stream()
                .flatMap(event -> alertsReferencing(alertsByReference, event).stream())
                .distinct().sorted(Comparator.comparing(Alert::occurredAt).thenComparing(Alert::id)).toList();
        List<String> eventIds = events.stream().map(SecurityEvent::eventId).toList();
        List<SecurityEventIdentity> eventIdentities = events.stream().map(SecurityEventIdentity::of).toList();
        List<String> alertIds = linkedAlerts.stream().map(Alert::id).toList();
        List<SecurityIncidentEvidence> evidence = events.stream()
                .map(SecurityIncidentService::evidenceFor).toList();
        List<SecurityIncidentTimelineEntry> timeline = new ArrayList<>();
        events.forEach(event -> timeline.add(new SecurityIncidentTimelineEntry("SECURITY_EVENT", event.eventId(),
                event.occurredAt(), event.eventType().name(), SecurityEventIdentity.of(event).reference())));
        linkedAlerts.forEach(alert -> timeline.add(new SecurityIncidentTimelineEntry("ALERT", alert.id(), alert.occurredAt(), "关联告警")));
        timeline.sort(Comparator.comparing(SecurityIncidentTimelineEntry::occurredAt).thenComparing(SecurityIncidentTimelineEntry::sourceId));
        SecurityIncidentRisk risk = linkedAlerts.isEmpty()
                ? SecurityIncidentRisk.MEDIUM
                : linkedAlerts.stream().anyMatch(alert -> alert.riskHint() == RiskLevel.HIGH)
                    ? SecurityIncidentRisk.HIGH : SecurityIncidentRisk.LOW;
        SecurityDispositionRecord sourceDisposition = events.stream()
                .map(SecurityEvent::disposition)
                .filter(record -> record.disposition() != SecurityDisposition.UNREVIEWED)
                .max(Comparator.comparing(SecurityDispositionRecord::decidedAt))
                .orElse(SecurityDispositionRecord.unreviewed());
        boolean sourceDecided = sourceDisposition.disposition() != SecurityDisposition.UNREVIEWED;
        return new SecurityIncident(incidentId(first), first.parkId(), first.buildingId(),
                correlationType(first), risk,
                sourceDecided ? SecurityIncidentStatus.REVIEWED : SecurityIncidentStatus.OPEN,
                events.get(0).occurredAt(), events.get(events.size() - 1).occurredAt(), eventIds, alertIds,
                evidence, timeline, recommendationsFor(risk),
                sourceDecided ? sourceDisposition.decidedAt() : null, null,
                sourceDisposition.disposition(), sourceDisposition, eventIdentities);
    }

    private static SecurityIncidentEvidence evidenceFor(SecurityEvent event) {
        return new SecurityIncidentEvidence(event.eventId(), event.occurredAt(), event.evidenceSummary(),
                event.rawEventType(), event.source().sourceType().name(), event.source().sourceId(),
                event.severity().name(), event.confidence());
    }

    private Map<AlertReferenceKey, List<Alert>> alertsByReference() {
        Map<AlertReferenceKey, List<Alert>> result = new HashMap<>();
        alerts.listActive().forEach(alert -> alert.evidence().stream()
                .filter(SecurityEventIdentity::isReference)
                .forEach(reference -> result.computeIfAbsent(
                        new AlertReferenceKey(reference, alert.parkId(), alert.buildingId()),
                        ignored -> new ArrayList<>()).add(alert)));
        return result;
    }

    /**
     * Alerts that reference a specific event. A source-qualified reference only
     * matches its own source, while the legacy bare reference keeps aliasing the
     * same source-local id across sources.
     */
    private static List<Alert> alertsReferencing(Map<AlertReferenceKey, List<Alert>> alertsByReference,
                                                 SecurityEvent event) {
        SecurityEventIdentity identity = SecurityEventIdentity.of(event);
        AlertReferenceKey qualified = new AlertReferenceKey(identity.reference(), event.parkId(), event.buildingId());
        AlertReferenceKey legacy = new AlertReferenceKey(
                SecurityEventIdentity.legacyReference(event.eventId()), event.parkId(), event.buildingId());
        if (qualified.equals(legacy)) return alertsByReference.getOrDefault(qualified, List.of());
        List<Alert> matched = new ArrayList<>(alertsByReference.getOrDefault(qualified, List.of()));
        matched.addAll(alertsByReference.getOrDefault(legacy, List.of()));
        return matched;
    }

    private List<SecurityIncident> restoreStates(List<SecurityIncident> freshIncidents) {
        List<SecurityIncident> stored = store.findAll();
        List<SecurityIncidentHandoff> retainedHandoffs = handoffs.list();
        Set<String> assignedStoredIncidentIds = new HashSet<>();
        // A stored resource may be discovered through a legacy source-less alias that
        // matches several concrete sources. Discovery is intentionally permissive, but
        // an alias-only match is claimed at most once per stored event identity, so an
        // unrelated source never inherits its state while a multi-event incident that
        // later splits can still hand its state to every uniquely matched window. A
        // fully-equal identity is a genuine overlap and may legitimately keep
        // propagating across a correlation resplit.
        Set<String> claimedIdentityLessIncidentIds = new HashSet<>();
        Map<String, Set<SecurityEventIdentity>> claimedIncidentAliasIdentities = new HashMap<>();
        Set<String> claimedIdentityLessHandoffWorkItemIds = new HashSet<>();
        Map<String, Set<SecurityEventIdentity>> claimedHandoffAliasIdentities = new HashMap<>();
        // A source-less stored event can be shadowed by several concrete sources that
        // reuse its id. The genuine enrichment is the copy whose occurrence time
        // matches the stored event, so reserve the alias for it before any fresh
        // incident — including an unrelated source that merely sorts first — claims it.
        Map<String, Map<SecurityEventIdentity, String>> preferredAliasClaimantIds = preferredAliasClaimants(stored,
                retainedHandoffs, freshIncidents);
        Set<String> correlatedRetainedHandoffWorkItemIds = new HashSet<>();
        List<SecurityIncident> candidatesForRetirement = new ArrayList<>();
        List<SecurityIncidentHandoff> retainedHandoffsForRetirement = new ArrayList<>();
        List<SecurityIncident> restoredIncidents = new ArrayList<>();
        for (SecurityIncident fresh : freshIncidents) {
            List<SecurityIncident> candidates = stored.stream()
                    .filter(existing -> sameCorrelation(existing, fresh))
                    .filter(existing -> overlaps(existing, fresh))
                    .filter(existing -> !reservedForAnotherClaimant(preferredAliasClaimantIds, existing, fresh))
                    .filter(existing -> sharesExactIdentity(existing, fresh)
                            || !aliasFullyClaimed(existing.eventIdentities(), fresh.eventIdentities(),
                                    claimedIdentityLessIncidentIds.contains(existing.incidentId()),
                                    claimedIncidentAliasIdentities.get(existing.incidentId())))
                    .toList();
            candidates.stream()
                    .filter(existing -> !sharesExactIdentity(existing, fresh))
                    .forEach(existing -> claimAliasIdentities(existing.incidentId(), existing.eventIdentities(),
                            fresh.eventIdentities(), claimedIdentityLessIncidentIds, claimedIncidentAliasIdentities));
            candidatesForRetirement.addAll(candidates);
            SecurityIncident canonical = candidates.stream()
                    .min(Comparator.comparing(SecurityIncident::openedAt).thenComparing(SecurityIncident::incidentId))
                    .orElse(null);
            boolean retainStoredIdentity = canonical != null
                    && assignedStoredIncidentIds.add(canonical.incidentId());
            SecurityIncident restored = restoreState(fresh, candidates, retainStoredIdentity);
            List<SecurityIncidentHandoff> matchingRetainedHandoffs = matchingRetainedHandoffs(fresh, restored,
                    retainedHandoffs).stream()
                    .filter(handoff -> !reservedForAnotherClaimant(preferredAliasClaimantIds, handoff, fresh))
                    .filter(handoff -> sharesExactIdentity(handoff, fresh)
                            || !aliasFullyClaimed(handoff.eventIdentities(), fresh.eventIdentities(),
                                    claimedIdentityLessHandoffWorkItemIds.contains(handoff.workItemId()),
                                    claimedHandoffAliasIdentities.get(handoff.workItemId())))
                    .toList();
            matchingRetainedHandoffs.stream()
                    .filter(handoff -> !sharesExactIdentity(handoff, fresh))
                    .forEach(handoff -> claimAliasIdentities(handoff.workItemId(), handoff.eventIdentities(),
                            fresh.eventIdentities(), claimedIdentityLessHandoffWorkItemIds,
                            claimedHandoffAliasIdentities));
            retainedHandoffsForRetirement.addAll(matchingRetainedHandoffs);
            matchingRetainedHandoffs.stream()
                    .map(SecurityIncidentHandoff::workItemId)
                    .forEach(correlatedRetainedHandoffWorkItemIds::add);
            restored = restoreHandoffProjection(fresh, restored, matchingRetainedHandoffs);
            restored = restoreRiskProjection(restored, candidates, matchingRetainedHandoffs);
            if (restored.handoffWorkItemId() != null) {
                correlatedRetainedHandoffWorkItemIds.add(restored.handoffWorkItemId());
            }
            restoredIncidents.add(restored);
        }
        Set<String> retainedRestoredIncidentIds = restoredIncidents.stream()
                .map(SecurityIncident::incidentId)
                .collect(java.util.stream.Collectors.toSet());
        stored.stream()
                .map(SecurityIncident::incidentId)
                .filter(incidentId -> !retainedRestoredIncidentIds.contains(incidentId))
                .distinct()
                .forEach(store::remove);
        retireSupersededHandoffs(restoredIncidents, candidatesForRetirement, retainedHandoffsForRetirement);
        retainedHandoffs.stream()
                .filter(handoff -> !correlatedRetainedHandoffWorkItemIds.contains(handoff.workItemId()))
                .forEach(handoff -> handoffs.retire(handoff.incidentId()));
        return List.copyOf(restoredIncidents);
    }

    private void retireSupersededHandoffs(List<SecurityIncident> restored, List<SecurityIncident> candidates,
                                          List<SecurityIncidentHandoff> retainedCandidates) {
        Set<String> retainedWorkItemIds = restored.stream()
                .map(SecurityIncident::handoffWorkItemId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        candidates.stream()
                .filter(existing -> existing.handoffWorkItemId() != null)
                .filter(existing -> !retainedWorkItemIds.contains(existing.handoffWorkItemId()))
                .forEach(existing -> handoffs.retire(existing.incidentId()));
        retainedCandidates.stream()
                .filter(existing -> !retainedWorkItemIds.contains(existing.workItemId()))
                .forEach(existing -> handoffs.retire(existing.incidentId()));
    }

    private static SecurityIncident restoreHandoffProjection(SecurityIncident fresh, SecurityIncident restored,
                                                             List<SecurityIncidentHandoff> retainedHandoffs) {
        SecurityIncidentHandoff handoff = restored.handoffWorkItemId() != null
                ? null
                : retainedHandoffs.stream()
                        .min(Comparator.comparing(SecurityIncidentHandoff::createdAt)
                                .thenComparing(SecurityIncidentHandoff::workItemId))
                        .orElse(null);
        if (restored.handoffWorkItemId() == null && handoff == null) return restored;
        // Several evicted handoffs can merge back together through a bridge event. Only a
        // single work item survives, but every matching handoff's decision must still be
        // reconciled so a human review carried by a handoff that is not selected — or by an
        // evicted handoff while a model-decided handoff stays in the store — is not silently
        // retired with the duplicate work item.
        List<SecurityDispositionRecord> decisions = new ArrayList<>(retainedHandoffs.size() + 1);
        decisions.add(restored.dispositionRecord());
        retainedHandoffs.forEach(each -> decisions.add(each.dispositionRecord()));
        SecurityDispositionRecord dispositionRecord = reconcileDisposition(fresh.dispositionRecord(), decisions);
        if (restored.handoffWorkItemId() != null) {
            if (dispositionRecord.equals(restored.dispositionRecord())) return restored;
            Instant reviewedAt = dispositionRecord.disposition() != SecurityDisposition.UNREVIEWED
                    ? dispositionRecord.decidedAt()
                    : restored.reviewedAt();
            return withStoredState(fresh, restored.incidentId(), restored.status(), reviewedAt,
                    restored.handoffWorkItemId(), fresh.riskLevel(), dispositionRecord.disposition(),
                    dispositionRecord);
        }
        Instant reviewedAt = dispositionRecord.disposition() != SecurityDisposition.UNREVIEWED
                ? dispositionRecord.decidedAt()
                : handoff.reviewedAt();
        return withStoredState(fresh, fresh.incidentId(), SecurityIncidentStatus.HANDOFF, reviewedAt,
                handoff.workItemId(), fresh.riskLevel(), dispositionRecord.disposition(), dispositionRecord);
    }

    private static SecurityIncident restoreRiskProjection(SecurityIncident restored,
                                                           List<SecurityIncident> storedCandidates,
                                                           List<SecurityIncidentHandoff> retainedHandoffs) {
        if (restored.status() != SecurityIncidentStatus.HANDOFF) return restored;
        SecurityIncidentRisk risk = restored.riskLevel();
        for (SecurityIncident candidate : storedCandidates) {
            risk = higherRisk(risk, candidate.riskLevel());
        }
        for (SecurityIncidentHandoff handoff : retainedHandoffs) {
            risk = higherRisk(risk, handoff.riskLevel());
        }
        if (risk == restored.riskLevel()) return restored;
        return withStoredState(restored, restored.incidentId(), restored.status(), restored.reviewedAt(),
                restored.handoffWorkItemId(), risk);
    }

    private static List<SecurityIncidentHandoff> matchingRetainedHandoffs(SecurityIncident fresh,
                                                                           SecurityIncident restored,
                                                                           List<SecurityIncidentHandoff> retainedHandoffs) {
        return retainedHandoffs.stream()
                .filter(existing -> existing.incidentId().equals(restored.incidentId())
                        || existing.incidentId().equals(fresh.incidentId())
                        || matchesCorrelation(existing, fresh))
                .toList();
    }

    private static boolean matchesCorrelation(SecurityIncidentHandoff handoff, SecurityIncident incident) {
        if (!Objects.equals(handoff.eventType(), incident.eventType())
                || !handoff.parkId().equals(incident.parkId())
                || !handoff.buildingId().equals(incident.buildingId())) {
            return false;
        }
        if (incident.eventIdentities().isEmpty() || handoff.eventIdentities().isEmpty()) return false;
        return incident.eventIdentities().stream()
                .anyMatch(identity -> handoff.eventIdentities().stream().anyMatch(identity::matches));
    }

    /**
     * Maps each source-qualified copy that aliases a stored incident or retained-only
     * handoff onto the copy whose occurrence time matches it. Without this, an unrelated
     * source that happens to reuse the id and sort earlier would inherit the stored state
     * or the retained human disposition. Reservations are keyed per stored event identity,
     * so a multi-event incident that later splits into several uniquely matched windows can
     * hand its finalized state to each split. Each identity is ranked by its own occurrence
     * time rather than the incident-wide latest event, so an unrelated copy near the window's
     * final event cannot win. A handoff whose incident is still stored is covered by the
     * incident reservation, and a handoff without a projected occurrence time cannot
     * disambiguate aliases at all.
     */
    private static Map<String, Map<SecurityEventIdentity, String>> preferredAliasClaimants(
            List<SecurityIncident> stored, List<SecurityIncidentHandoff> retainedHandoffs,
            List<SecurityIncident> freshIncidents) {
        Map<String, Map<SecurityEventIdentity, String>> preferred = new HashMap<>();
        Set<String> storedIncidentIds = new HashSet<>();
        for (SecurityIncident existing : stored) {
            storedIncidentIds.add(existing.incidentId());
            if (existing.eventIdentities().isEmpty()) {
                reservePreferredClaimant(preferred, existing.incidentId(), null, existing.lastOccurredAt(),
                        freshIncidents.stream()
                                .filter(fresh -> sameCorrelation(existing, fresh))
                                .filter(fresh -> overlaps(existing, fresh))
                                .filter(fresh -> !sharesExactIdentity(existing, fresh))
                                .toList());
                continue;
            }
            for (SecurityEventIdentity identity : existing.eventIdentities()) {
                reservePreferredClaimant(preferred, existing.incidentId(), identity,
                        identityOccurredAt(existing, identity),
                        freshIncidents.stream()
                                .filter(fresh -> sameCorrelation(existing, fresh))
                                .filter(fresh -> aliases(fresh, identity))
                                .toList());
            }
        }
        for (SecurityIncidentHandoff handoff : retainedHandoffs) {
            if (storedIncidentIds.contains(handoff.incidentId())) continue;
            for (SecurityEventIdentity identity : handoff.eventIdentities()) {
                reservePreferredClaimant(preferred, handoff.incidentId(), identity,
                        identityOccurredAt(handoff, identity),
                        freshIncidents.stream()
                                .filter(fresh -> matchesCorrelation(handoff, fresh))
                                .filter(fresh -> aliases(fresh, identity))
                                .toList());
            }
        }
        return preferred;
    }

    /**
     * The occurrence time recorded for {@code identity} within {@code incident}. A correlation
     * window can hold events at different times, so ranking an identity's alias copies against
     * the incident-wide {@code lastOccurredAt} can prefer an unrelated copy near the window's
     * final event over the genuine enrichment. Evidence that does not carry the identity falls
     * back to the incident-wide time so legacy projections still rank deterministically.
     */
    private static Instant identityOccurredAt(SecurityIncident incident, SecurityEventIdentity identity) {
        return incident.evidence().stream()
                .filter(evidence -> identity.matches(evidence.eventIdentity(incident.parkId(), incident.buildingId())))
                .map(evidence -> evidence.occurredAt())
                .max(Comparator.naturalOrder())
                .orElse(incident.lastOccurredAt());
    }

    /** A handoff without a projected time for {@code identity} falls back to its latest event. */
    private static Instant identityOccurredAt(SecurityIncidentHandoff handoff, SecurityEventIdentity identity) {
        Instant projected = handoff.identityOccurredAt().get(identity);
        return projected != null ? projected : handoff.lastOccurredAt();
    }

    /** True when {@code fresh} reuses {@code identity} through a different or source-less copy. */
    private static boolean aliases(SecurityIncident fresh, SecurityEventIdentity identity) {
        return !fresh.eventIdentities().contains(identity)
                && fresh.eventIdentities().stream().anyMatch(identity::matches);
    }

    /**
     * The identities {@code storedIdentities} shares with {@code freshIdentities} through a
     * legacy source-less alias. Empty when either side carries no identity or the two sides
     * share a fully-equal identity, which is a genuine overlap rather than an alias.
     */
    private static List<SecurityEventIdentity> aliasIdentities(List<SecurityEventIdentity> storedIdentities,
                                                               List<SecurityEventIdentity> freshIdentities) {
        List<SecurityEventIdentity> aliases = new ArrayList<>();
        for (SecurityEventIdentity identity : storedIdentities) {
            if (freshIdentities.contains(identity)) return List.of();
            if (freshIdentities.stream().anyMatch(identity::matches)) aliases.add(identity);
        }
        return aliases;
    }

    /**
     * True when every alias identity a stored projection shares with {@code fresh} has
     * already been claimed by an earlier window. A projection that uniquely aliases even
     * one identity — such as one split of a multi-event incident — stays available, so
     * every uniquely matched window can still retain finalized state.
     */
    private static boolean aliasFullyClaimed(List<SecurityEventIdentity> storedIdentities,
                                             List<SecurityEventIdentity> freshIdentities, boolean identityLessClaimed,
                                             Set<SecurityEventIdentity> claimedIdentities) {
        if (storedIdentities.isEmpty() || freshIdentities.isEmpty()) return identityLessClaimed;
        List<SecurityEventIdentity> aliases = aliasIdentities(storedIdentities, freshIdentities);
        return !aliases.isEmpty() && claimedIdentities != null && claimedIdentities.containsAll(aliases);
    }

    private static void claimAliasIdentities(String ownerId, List<SecurityEventIdentity> storedIdentities,
                                             List<SecurityEventIdentity> freshIdentities,
                                             Set<String> identityLessOwners,
                                             Map<String, Set<SecurityEventIdentity>> claimedIdentities) {
        if (storedIdentities.isEmpty() || freshIdentities.isEmpty()) {
            identityLessOwners.add(ownerId);
            return;
        }
        List<SecurityEventIdentity> aliases = aliasIdentities(storedIdentities, freshIdentities);
        if (aliases.isEmpty()) return;
        claimedIdentities.computeIfAbsent(ownerId, ignored -> new HashSet<>()).addAll(aliases);
    }

    private static void reservePreferredClaimant(Map<String, Map<SecurityEventIdentity, String>> preferred,
                                                 String incidentId, SecurityEventIdentity identity, Instant occurredAt,
                                                 List<SecurityIncident> aliasClaimants) {
        if (occurredAt == null || aliasClaimants.size() <= 1) return;
        aliasClaimants.stream()
                .min(Comparator.comparingLong((SecurityIncident fresh) ->
                                aliasDistanceMillis(occurredAt, fresh, identity))
                        .thenComparing(SecurityIncident::incidentId))
                .ifPresent(fresh -> preferred
                        .computeIfAbsent(incidentId, ignored -> new HashMap<>())
                        .put(identity, fresh.incidentId()));
    }

    private static long aliasDistanceMillis(Instant storedOccurredAt, SecurityIncident fresh,
                                            SecurityEventIdentity identity) {
        Instant freshOccurredAt = identity == null ? fresh.lastOccurredAt() : identityOccurredAt(fresh, identity);
        return Math.abs(Duration.between(freshOccurredAt, storedOccurredAt).toMillis());
    }

    private static boolean reservedForAnotherClaimant(Map<String, Map<SecurityEventIdentity, String>> preferred,
                                                      SecurityIncident existing, SecurityIncident fresh) {
        if (sharesExactIdentity(existing, fresh)) return false;
        return reservedForAnotherClaimant(preferred, existing.incidentId(), existing.eventIdentities(), fresh);
    }

    private static boolean reservedForAnotherClaimant(Map<String, Map<SecurityEventIdentity, String>> preferred,
                                                      SecurityIncidentHandoff handoff, SecurityIncident fresh) {
        if (sharesExactIdentity(handoff, fresh)) return false;
        return reservedForAnotherClaimant(preferred, handoff.incidentId(), handoff.eventIdentities(), fresh);
    }

    /**
     * True only when every alias identity a stored projection shares with {@code fresh} is
     * reserved for another claimant. A projection that uniquely aliases even one identity —
     * such as one split of a multi-event incident — may still reclaim its finalized state.
     */
    private static boolean reservedForAnotherClaimant(Map<String, Map<SecurityEventIdentity, String>> preferred,
                                                      String incidentId, List<SecurityEventIdentity> storedIdentities,
                                                      SecurityIncident fresh) {
        Map<SecurityEventIdentity, String> reservations = preferred.get(incidentId);
        if (reservations == null) return false;
        if (storedIdentities.isEmpty()) {
            String reserved = reservations.get(null);
            return reserved != null && !reserved.equals(fresh.incidentId());
        }
        boolean sharedAlias = false;
        for (SecurityEventIdentity identity : storedIdentities) {
            if (fresh.eventIdentities().stream().noneMatch(identity::matches)) continue;
            if (fresh.eventIdentities().contains(identity)) return false;
            sharedAlias = true;
            String reserved = reservations.get(identity);
            if (reserved == null || reserved.equals(fresh.incidentId())) return false;
        }
        return sharedAlias;
    }

    private SecurityIncident restoreState(SecurityIncident fresh, List<SecurityIncident> candidates,
                                          boolean retainStoredIdentity) {
        if (candidates.isEmpty()) return fresh;

        SecurityIncident canonical = candidates.stream()
                .min(Comparator.comparing(SecurityIncident::openedAt).thenComparing(SecurityIncident::incidentId))
                .orElseThrow();
        List<String> handoffIds = candidates.stream()
                .map(SecurityIncident::handoffWorkItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        SecurityIncident state = candidates.stream()
                .max(Comparator.comparingInt((SecurityIncident existing) -> statusRank(existing.status()))
                        .thenComparing(existing -> existing.incidentId().equals(canonical.incidentId()) ? 1 : 0)
                        .thenComparing(SecurityIncident::incidentId))
                .orElseThrow();
        Instant reviewedAt = candidates.stream()
                .map(SecurityIncident::reviewedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        String handoffWorkItemId = state.handoffWorkItemId();
        if (handoffWorkItemId == null && !handoffIds.isEmpty()) handoffWorkItemId = handoffIds.get(0);
        String incidentId = retainStoredIdentity ? canonical.incidentId() : fresh.incidentId();
        SecurityDispositionRecord dispositionRecord = effectiveDisposition(fresh, candidates);
        Instant effectiveReviewedAt = dispositionRecord.disposition() != SecurityDisposition.UNREVIEWED
                ? dispositionRecord.decidedAt()
                : latestReviewedAt(reviewedAt, fresh.reviewedAt());
        return withStoredState(fresh, incidentId, higherStatus(state.status(), fresh.status()), effectiveReviewedAt,
                handoffWorkItemId, fresh.riskLevel(), dispositionRecord.disposition(), dispositionRecord);
    }

    /**
     * Resolves the disposition to keep when fresh evidence overlaps stored state.
     * A stored human review stays authoritative and is never replaced by a later
     * source decision; otherwise the newest decided record — including the freshly
     * correlated one — wins, so a source can correct an earlier decision and a
     * first poll that only returned {@code UNREVIEWED} can still be upgraded.
     */
    private static SecurityDispositionRecord effectiveDisposition(SecurityIncident fresh,
                                                                  List<SecurityIncident> candidates) {
        List<SecurityDispositionRecord> stored = new ArrayList<>();
        candidates.forEach(candidate -> stored.add(candidate.dispositionRecord()));
        return reconcileDisposition(fresh.dispositionRecord(), stored);
    }

    /**
     * Reconciles a freshly correlated record with stored/projected records.
     * A stored human review stays authoritative and is never replaced by a later
     * source decision; otherwise the newest decided record — including the fresh
     * one — wins, so a source can correct an earlier decision and an
     * {@code UNREVIEWED} first poll can still be upgraded.
     */
    private static SecurityDispositionRecord reconcileDisposition(SecurityDispositionRecord fresh,
                                                                  List<SecurityDispositionRecord> stored) {
        SecurityDispositionRecord storedHumanReview = stored.stream()
                .filter(record -> record.source() == SecurityDispositionSource.HUMAN_REVIEW)
                .min(Comparator.comparing(SecurityDispositionRecord::decidedAt))
                .orElse(null);
        if (storedHumanReview != null) return storedHumanReview;
        List<SecurityDispositionRecord> decided = new ArrayList<>(stored);
        decided.add(fresh);
        return decided.stream()
                .filter(record -> record.disposition() != SecurityDisposition.UNREVIEWED)
                .max(Comparator.comparing(SecurityDispositionRecord::decidedAt))
                .orElse(SecurityDispositionRecord.unreviewed());
    }

    private static Instant latestReviewedAt(Instant storedReviewedAt, Instant freshReviewedAt) {
        if (storedReviewedAt == null) return freshReviewedAt;
        if (freshReviewedAt == null) return storedReviewedAt;
        return freshReviewedAt.isAfter(storedReviewedAt) ? freshReviewedAt : storedReviewedAt;
    }

    private static SecurityIncidentStatus higherStatus(SecurityIncidentStatus left, SecurityIncidentStatus right) {
        return statusRank(left) >= statusRank(right) ? left : right;
    }

    private static boolean overlaps(SecurityIncident left, SecurityIncident right) {
        if (!left.eventIdentities().isEmpty() && !right.eventIdentities().isEmpty()) {
            return left.eventIdentities().stream()
                    .anyMatch(one -> right.eventIdentities().stream().anyMatch(one::matches));
        }
        return left.eventIds().stream().anyMatch(right.eventIds()::contains);
    }

    /**
     * True when two incidents share a fully-equal logical identity. Unlike
     * {@link #overlaps}, a legacy source-less alias does not count: alias-only
     * matches are resolved to at most one concrete source by the caller.
     */
    private static boolean sharesExactIdentity(SecurityIncident left, SecurityIncident right) {
        if (left.eventIdentities().isEmpty() || right.eventIdentities().isEmpty()) {
            return left.eventIds().stream().anyMatch(right.eventIds()::contains);
        }
        return left.eventIdentities().stream().anyMatch(right.eventIdentities()::contains);
    }

    private static boolean sharesExactIdentity(SecurityIncidentHandoff handoff, SecurityIncident incident) {
        return handoff.eventIdentities().stream().anyMatch(incident.eventIdentities()::contains);
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId) {
        return withStoredState(fresh, incidentId, status, reviewedAt, handoffWorkItemId, fresh.riskLevel());
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId,
                                                    SecurityIncidentRisk riskLevel) {
        return withStoredState(fresh, incidentId, status, reviewedAt, handoffWorkItemId, riskLevel,
                fresh.disposition(), fresh.dispositionRecord());
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId,
                                                    SecurityIncidentRisk riskLevel,
                                                    SecurityDisposition disposition,
                                                    SecurityDispositionRecord dispositionRecord) {
        return new SecurityIncident(incidentId, fresh.parkId(), fresh.buildingId(), fresh.eventType(),
                riskLevel, status, fresh.openedAt(), fresh.lastOccurredAt(), fresh.eventIds(), fresh.alertIds(),
                fresh.evidence(), fresh.timeline(), status == SecurityIncidentStatus.HANDOFF
                        ? recommendationsFor(riskLevel) : fresh.recommendations(), reviewedAt, handoffWorkItemId,
                disposition, dispositionRecord, fresh.eventIdentities());
    }

    private static List<String> recommendationsFor(SecurityIncidentRisk risk) {
        return risk == SecurityIncidentRisk.HIGH
                ? List.of("核对安全处置手册并由授权人员复核。", "必要时记录协同交接并保留人工审计。")
                : List.of("核对安全处置手册并记录研判结论。");
    }

    private static SecurityIncidentRisk higherRisk(SecurityIncidentRisk left, SecurityIncidentRisk right) {
        return riskRank(right) > riskRank(left) ? right : left;
    }

    private static int statusRank(SecurityIncidentStatus status) {
        return switch (status) {
            case OPEN -> 0;
            case REVIEWED -> 1;
            case HANDOFF -> 2;
        };
    }

    private static boolean sameCorrelation(SecurityIncident left, SecurityIncident right) {
        return left.parkId().equals(right.parkId())
                && left.buildingId().equals(right.buildingId())
                && left.eventType().equals(right.eventType());
    }

    /**
     * Discriminates the correlation bucket's event type. Unsupported vendor codes
     * all standardize to {@code UNKNOWN}; falling back to the raw vendor type keeps
     * unrelated vendor events in the same park/building/window from being merged
     * into a single incident just because the enum mapping covered neither code.
     */
    private static String correlationType(SecurityEvent event) {
        if (event.eventType() != SecurityEventType.UNKNOWN) return event.eventType().name();
        String raw = event.rawEventType();
        return raw == null || raw.isBlank() ? event.eventType().name() : event.eventType().name() + ":" + raw;
    }

    private static CorrelationKey bucketKey(SecurityEvent event) {
        return new CorrelationKey(event.parkId(), event.buildingId(), correlationType(event));
    }

    private static String incidentId(SecurityEvent event) {
        CorrelationKey key = bucketKey(event);
        String material = encode(key.parkId()) + ":" + encode(key.buildingId()) + ":"
                + encode(key.eventType()) + ":" + SecurityEventIdentity.of(event).material();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "INC:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encode(String value) {
        return value.length() + "#" + value;
    }

    private static int riskRank(SecurityIncidentRisk risk) {
        return switch (risk) {
            case HIGH -> 2;
            case MEDIUM -> 1;
            case LOW -> 0;
        };
    }

    /** Result of a review request: the persisted incident and whether it changed. */
    public record ReviewOutcome(SecurityIncident incident, boolean applied) {
        public ReviewOutcome {
            Objects.requireNonNull(incident, "incident");
        }
    }

    private record CorrelationKey(String parkId, String buildingId, String eventType) {
    }

    private record AlertReferenceKey(String reference, String parkId, String buildingId) {
    }
}
