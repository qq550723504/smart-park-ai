package com.example.smartpark.collaboration.expert;

import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces that a model can only cite evidence observed during this invocation. */
public final class ExpertFindingValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ExpertFinding validate(ExpertFinding finding, Set<String> observedEvidenceRefs) {
        Objects.requireNonNull(finding, "finding");
        Set<String> observed = Set.copyOf(Objects.requireNonNull(observedEvidenceRefs, "observedEvidenceRefs"));
        List<EvidenceLedger.Observation> observations = observed.stream()
                .map(ref -> new EvidenceLedger.Observation(ref, ""))
                .toList();
        return validateWithObservations(finding, observations);
    }

    public ExpertFinding validateWithObservations(ExpertFinding finding,
                                                  Collection<EvidenceLedger.Observation> observations) {
        Objects.requireNonNull(finding, "finding");
        Map<String, EvidenceLedger.Observation> observed = Objects.requireNonNull(observations,
                        "observations").stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        EvidenceLedger.Observation::ref, value -> value, (left, right) -> right));
        List<String> refs = finding.evidenceRefs();
        boolean validRefs = !refs.isEmpty() && new HashSet<>(observed.keySet()).containsAll(refs);
        boolean supportedClaim = validRefs && claimMatchesToolResults(finding, refs, observed);
        if (finding.status() == FindingStatus.SUPPORTED && !supportedClaim) {
            return new ExpertFinding(finding.domain(), FindingStatus.INSUFFICIENT_EVIDENCE,
                    "Insufficient evidence: the finding is not supported by the cited tool results.", List.of(), 0,
                    List.of("repeat the domain tool lookup"));
        }
        return finding;
    }

    private boolean claimMatchesToolResults(ExpertFinding finding, List<String> refs,
                                            Map<String, EvidenceLedger.Observation> observed) {
        StatusClaim claim = StatusClaim.from(finding.conclusion());
        if (claim == StatusClaim.NONE) return true;

        boolean foundStatus = false;
        for (String ref : refs) {
            for (String status : statuses(observed.get(ref).result())) {
                foundStatus = true;
                if (!claim.matches(status)) return false;
            }
        }
        return foundStatus;
    }

    private static Set<String> statuses(String result) {
        Set<String> statuses = new java.util.LinkedHashSet<>();
        try {
            JsonNode root = JSON.readTree(result);
            collectStatusValues(root, statuses);
        } catch (Exception ignored) {
            // A non-JSON result cannot establish a structured status claim.
        }
        return Set.copyOf(statuses);
    }

    private static void collectStatusValues(JsonNode node, Set<String> statuses) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("status".equalsIgnoreCase(entry.getKey()) && entry.getValue().isTextual()) {
                    statuses.add(entry.getValue().asText().trim().toUpperCase(Locale.ROOT));
                }
                collectStatusValues(entry.getValue(), statuses);
            });
        } else if (node.isArray()) {
            node.forEach(value -> collectStatusValues(value, statuses));
        }
    }

    private enum StatusClaim {
        ONLINE, OFFLINE, UNKNOWN, NONE;

        static StatusClaim from(String conclusion) {
            String normalized = conclusion.toLowerCase(Locale.ROOT);
            if (normalized.matches(".*(\\boffline\\b|离线|脱机|不可用).*")) return OFFLINE;
            if (normalized.matches(".*(\\bonline\\b|在线|正常运行).*")) return ONLINE;
            if (normalized.matches(".*(\\bunknown\\b|未知|无法确定).*")) return UNKNOWN;
            return NONE;
        }

        boolean matches(String actual) {
            return switch (this) {
                case ONLINE -> "ONLINE".equals(actual);
                case OFFLINE -> "OFFLINE".equals(actual);
                case UNKNOWN -> "UNKNOWN".equals(actual);
                case NONE -> true;
            };
        }
    }
}
