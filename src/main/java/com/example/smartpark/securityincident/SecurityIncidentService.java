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
        List<SecurityIncident> filtered = currentIncidents(query.status());
        return new SecurityIncidentPage(filtered.stream().skip(query.offset()).limit(query.limit()).toList(), filtered.size());
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
        Map<AlertEventKey, List<Alert>> alertsByEvent = alertsByEvent();
        List<SecurityIncident> incidents = new ArrayList<>();
        buckets.values().forEach(events -> splitBucket(events, alertsByEvent, incidents));
        return incidents;
    }

    private void splitBucket(List<SecurityEvent> events, Map<AlertEventKey, List<Alert>> alertsByEvent,
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

    private SecurityIncident build(List<SecurityEvent> events, Map<AlertEventKey, List<Alert>> alertsByEvent) {
        SecurityEvent first = events.get(0);
        List<Alert> linkedAlerts = events.stream()
                .flatMap(event -> alertsByEvent.getOrDefault(
                        new AlertEventKey(event.eventId(), event.parkId(), event.buildingId()), List.of()).stream())
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
        return new SecurityIncident(incidentId(first), first.parkId(), first.buildingId(),
                first.eventType(), risk, SecurityIncidentStatus.OPEN, events.get(0).occurredAt(),
                events.get(events.size() - 1).occurredAt(), eventIds, alertIds, evidence, timeline,
                recommendationsFor(risk), null, null);
    }

    private Map<AlertEventKey, List<Alert>> alertsByEvent() {
        Map<AlertEventKey, List<Alert>> result = new HashMap<>();
        alerts.listActive().forEach(alert -> alert.evidence().stream()
                .filter(token -> token.startsWith(SECURITY_EVENT_PREFIX))
                .map(token -> token.substring(SECURITY_EVENT_PREFIX.length()))
                .filter(id -> !id.isBlank())
                .forEach(id -> result.computeIfAbsent(new AlertEventKey(id, alert.parkId(), alert.buildingId()),
                        ignored -> new ArrayList<>()).add(alert)));
        return result;
    }

    private List<SecurityIncident> restoreStates(List<SecurityIncident> freshIncidents) {
        List<SecurityIncident> stored = store.findAll();
        List<SecurityIncidentHandoff> retainedHandoffs = handoffs.list();
        Set<String> assignedStoredIncidentIds = new HashSet<>();
        Set<String> correlatedRetainedHandoffWorkItemIds = new HashSet<>();
        List<SecurityIncident> candidatesForRetirement = new ArrayList<>();
        List<SecurityIncidentHandoff> retainedHandoffsForRetirement = new ArrayList<>();
        List<SecurityIncident> restoredIncidents = new ArrayList<>();
        for (SecurityIncident fresh : freshIncidents) {
            List<SecurityIncident> candidates = stored.stream()
                    .filter(existing -> sameCorrelation(existing, fresh))
                    .filter(existing -> overlaps(existing, fresh))
                    .toList();
            candidatesForRetirement.addAll(candidates);
            SecurityIncident canonical = candidates.stream()
                    .min(Comparator.comparing(SecurityIncident::openedAt).thenComparing(SecurityIncident::incidentId))
                    .orElse(null);
            boolean retainStoredIdentity = canonical != null
                    && assignedStoredIncidentIds.add(canonical.incidentId());
            SecurityIncident restored = restoreState(fresh, candidates, retainStoredIdentity);
            List<SecurityIncidentHandoff> matchingRetainedHandoffs = matchingRetainedHandoffs(fresh, restored,
                    retainedHandoffs);
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
        if (restored.handoffWorkItemId() != null) return restored;
        SecurityIncidentHandoff handoff = retainedHandoffs.stream()
                .min(Comparator.comparing(SecurityIncidentHandoff::createdAt)
                        .thenComparing(SecurityIncidentHandoff::workItemId))
                .orElse(null);
        if (handoff == null) return restored;
        return withStoredState(fresh, fresh.incidentId(), SecurityIncidentStatus.HANDOFF, handoff.reviewedAt(),
                handoff.workItemId());
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
        return Objects.equals(handoff.eventType(), incident.eventType())
                && handoff.parkId().equals(incident.parkId())
                && handoff.buildingId().equals(incident.buildingId())
                && !handoff.eventIds().isEmpty()
                && handoff.eventIds().stream().anyMatch(incident.eventIds()::contains);
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
        return withStoredState(fresh, incidentId, state.status(), reviewedAt, handoffWorkItemId);
    }

    private static boolean overlaps(SecurityIncident left, SecurityIncident right) {
        return left.eventIds().stream().anyMatch(right.eventIds()::contains);
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId) {
        return withStoredState(fresh, incidentId, status, reviewedAt, handoffWorkItemId, fresh.riskLevel());
    }

    private static SecurityIncident withStoredState(SecurityIncident fresh, String incidentId, SecurityIncidentStatus status,
                                                    Instant reviewedAt, String handoffWorkItemId,
                                                    SecurityIncidentRisk riskLevel) {
        return new SecurityIncident(incidentId, fresh.parkId(), fresh.buildingId(), fresh.eventType(),
                riskLevel, status, fresh.openedAt(), fresh.lastOccurredAt(), fresh.eventIds(), fresh.alertIds(),
                fresh.evidence(), fresh.timeline(), status == SecurityIncidentStatus.HANDOFF
                        ? recommendationsFor(riskLevel) : fresh.recommendations(), reviewedAt, handoffWorkItemId);
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

    private record AlertEventKey(String eventId, String parkId, String buildingId) {
    }
}
