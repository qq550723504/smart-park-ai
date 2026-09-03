package com.example.smartpark.securityincident;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;

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
    private static final String SECURITY_EVENT_PREFIX = "security-event:";

    private final SecurityEventReader security;
    private final AlertPort alerts;
    private final SecurityIncidentStore store;
    private final SecurityIncidentHandoffPort handoffs;
    private final Clock clock;

    public SecurityIncidentService(SecurityEventReader security, AlertPort alerts, SecurityIncidentStore store,
                                   SecurityIncidentHandoffPort handoffs, Clock clock) {
        this.security = Objects.requireNonNull(security, "security");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.store = Objects.requireNonNull(store, "store");
        this.handoffs = Objects.requireNonNull(handoffs, "handoffs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized SecurityIncidentPage list(SecurityIncidentQuery query) {
        Objects.requireNonNull(query, "query");
        List<SecurityIncident> correlated = correlate();
        List<SecurityIncident> merged = restoreStates(correlated);
        merged.forEach(incident -> {
            store.save(incident);
            if (incident.status() == SecurityIncidentStatus.HANDOFF) {
                handoffs.refresh(incident, clock.instant());
            }
        });
        java.util.Set<String> currentIds = merged.stream().map(SecurityIncident::incidentId)
                .collect(java.util.stream.Collectors.toSet());
        List<SecurityIncident> filtered = store.findAll().stream()
                .filter(incident -> currentIds.contains(incident.incidentId()))
                .filter(incident -> query.status() == null || incident.status() == query.status())
                .sorted(Comparator.comparing(SecurityIncident::riskLevel, Comparator.comparingInt(SecurityIncidentService::riskRank).reversed())
                        .thenComparing(SecurityIncident::lastOccurredAt, Comparator.reverseOrder())
                        .thenComparing(SecurityIncident::incidentId))
                .toList();
        return new SecurityIncidentPage(filtered.stream().limit(query.limit()).toList(), filtered.size());
    }

    public synchronized SecurityIncident get(String incidentId) {
        return list(new SecurityIncidentQuery(null, SecurityIncidentQuery.MAX_LIMIT)).items().stream()
                .filter(incident -> incident.incidentId().equals(incidentId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("security incident not found"));
    }

    public synchronized SecurityIncident review(String incidentId) {
        SecurityIncident current = get(incidentId);
        SecurityIncident reviewed = current.review(clock.instant());
        store.save(reviewed);
        return reviewed;
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
        Map<CorrelationKey, List<SecurityEvent>> buckets = new LinkedHashMap<>();
        security.listEvents().stream()
                .sorted(Comparator.comparing(SecurityEvent::occurredAt).thenComparing(SecurityEvent::eventId))
                .forEach(event -> buckets.computeIfAbsent(bucketKey(event), ignored -> new ArrayList<>()).add(event));
        Map<String, List<Alert>> alertsByEvent = alertsByEvent();
        List<SecurityIncident> incidents = new ArrayList<>();
        buckets.values().forEach(events -> splitBucket(events, alertsByEvent, incidents));
        return incidents;
    }

    private void splitBucket(List<SecurityEvent> events, Map<String, List<Alert>> alertsByEvent,
                             List<SecurityIncident> target) {
        List<SecurityEvent> current = new ArrayList<>();
        for (SecurityEvent event : events) {
            if (!current.isEmpty() && Duration.between(current.get(current.size() - 1).occurredAt(), event.occurredAt()).compareTo(GROUPING_WINDOW) > 0) {
                target.add(build(current, alertsByEvent));
                current.clear();
            }
            current.add(event);
        }
        if (!current.isEmpty()) target.add(build(current, alertsByEvent));
    }

    private SecurityIncident build(List<SecurityEvent> events, Map<String, List<Alert>> alertsByEvent) {
        SecurityEvent first = events.get(0);
        List<Alert> linkedAlerts = events.stream().flatMap(event -> alertsByEvent.getOrDefault(event.eventId(), List.of()).stream())
                .distinct().sorted(Comparator.comparing(Alert::occurredAt).thenComparing(Alert::id)).toList();
        List<String> eventIds = events.stream().map(SecurityEvent::eventId).toList();
        List<String> alertIds = linkedAlerts.stream().map(Alert::id).toList();
        List<SecurityIncidentEvidence> evidence = events.stream()
                .map(event -> new SecurityIncidentEvidence(event.eventId(), event.occurredAt(), event.evidenceSummary())).toList();
        List<SecurityIncidentTimelineEntry> timeline = new ArrayList<>();
        events.forEach(event -> timeline.add(new SecurityIncidentTimelineEntry("SECURITY_EVENT", event.eventId(), event.occurredAt(), event.eventType())));
        linkedAlerts.forEach(alert -> timeline.add(new SecurityIncidentTimelineEntry("ALERT", alert.id(), alert.occurredAt(), "关联告警")));
        timeline.sort(Comparator.comparing(SecurityIncidentTimelineEntry::occurredAt).thenComparing(SecurityIncidentTimelineEntry::sourceId));
        SecurityIncidentRisk risk = linkedAlerts.isEmpty()
                ? SecurityIncidentRisk.MEDIUM
                : linkedAlerts.stream().anyMatch(alert -> alert.riskHint() == RiskLevel.HIGH)
                    ? SecurityIncidentRisk.HIGH : SecurityIncidentRisk.LOW;
        List<String> recommendations = risk == SecurityIncidentRisk.HIGH
                ? List.of("核对安全处置手册并由授权人员复核。", "必要时记录协同交接并保留人工审计。")
                : List.of("核对安全处置手册并记录研判结论。");
        return new SecurityIncident(incidentId(first), first.parkId(), first.buildingId(),
                first.eventType(), risk, SecurityIncidentStatus.OPEN, events.get(0).occurredAt(),
                events.get(events.size() - 1).occurredAt(), eventIds, alertIds, evidence, timeline, recommendations, null, null);
    }

    private Map<String, List<Alert>> alertsByEvent() {
        Map<String, List<Alert>> result = new HashMap<>();
        alerts.listActive().forEach(alert -> alert.evidence().stream()
                .filter(token -> token.startsWith(SECURITY_EVENT_PREFIX))
                .map(token -> token.substring(SECURITY_EVENT_PREFIX.length()))
                .filter(id -> !id.isBlank())
                .forEach(id -> result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(alert)));
        return result;
    }

    private List<SecurityIncident> restoreStates(List<SecurityIncident> freshIncidents) {
        Set<String> consumed = new HashSet<>();
        List<SecurityIncident> stored = store.findAll();
        return freshIncidents.stream().map(fresh -> {
            List<SecurityIncident> candidates = stored.stream()
                    .filter(existing -> !consumed.contains(existing.incidentId()))
                    .filter(existing -> sameCorrelation(existing, fresh))
                    .filter(existing -> overlaps(existing, fresh))
                    .toList();
            SecurityIncident restored = restoreState(fresh, candidates);
            candidates.forEach(existing -> consumed.add(existing.incidentId()));
            return restored;
        }).toList();
    }

    private SecurityIncident restoreState(SecurityIncident fresh, List<SecurityIncident> candidates) {
        if (candidates.isEmpty()) return fresh;

        SecurityIncident canonical = candidates.stream()
                .min(Comparator.comparing(SecurityIncident::openedAt).thenComparing(SecurityIncident::incidentId))
                .orElseThrow();
        List<String> handoffIds = candidates.stream()
                .map(SecurityIncident::handoffWorkItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        boolean conflictingHandoffs = handoffIds.size() > 1;
        SecurityIncident state = conflictingHandoffs ? canonical : candidates.stream()
                .max(Comparator.comparingInt(existing -> statusRank(existing.status())))
                .orElseThrow();
        Instant reviewedAt = conflictingHandoffs ? canonical.reviewedAt() : candidates.stream()
                .map(SecurityIncident::reviewedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        String handoffWorkItemId = conflictingHandoffs ? canonical.handoffWorkItemId()
                : handoffIds.isEmpty() ? null : handoffIds.get(0);
        return withStoredState(fresh, canonical.incidentId(), state.status(), reviewedAt, handoffWorkItemId);
    }

    private static boolean overlaps(SecurityIncident left, SecurityIncident right) {
        return left.eventIds().stream().anyMatch(right.eventIds()::contains);
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId) {
        return new SecurityIncident(incidentId, fresh.parkId(), fresh.buildingId(), fresh.eventType(),
                fresh.riskLevel(), status, fresh.openedAt(), fresh.lastOccurredAt(), fresh.eventIds(), fresh.alertIds(),
                fresh.evidence(), fresh.timeline(), fresh.recommendations(), reviewedAt, handoffWorkItemId);
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

    private static CorrelationKey bucketKey(SecurityEvent event) {
        return new CorrelationKey(event.parkId(), event.buildingId(), event.eventType());
    }

    private static String incidentId(SecurityEvent event) {
        CorrelationKey key = bucketKey(event);
        String material = encode(key.parkId()) + ":" + encode(key.buildingId()) + ":"
                + encode(key.eventType()) + ":" + encode(event.eventId());
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

    private record CorrelationKey(String parkId, String buildingId, String eventType) {
    }
}
