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

/** Enforces that supported findings are derived from results observed during this invocation. */
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
        if (finding.status() == FindingStatus.SUPPORTED) {
            boolean usableResults = validRefs && refs.stream()
                    .map(observed::get)
                    .allMatch(ExpertFindingValidator::isUsableObservation);
            boolean supportedClaim = usableResults && claimMatchesToolResults(finding, refs, observed);
            if (!supportedClaim) {
                return new ExpertFinding(finding.domain(), FindingStatus.INSUFFICIENT_EVIDENCE,
                        "Insufficient evidence: the finding is not supported by the cited tool results.",
                        List.of(), 0, List.of("repeat the domain tool lookup"));
            }
            // Free-form model prose cannot be validated generically. Replace it
            // with an exact, deterministic rendering of the cited observations
            // so no uncited quantitative or qualitative claim reaches synthesis.
            return new ExpertFinding(finding.domain(), finding.status(),
                    groundedConclusion(refs, observed), refs, finding.confidence(), finding.nextChecks());
        }
        return finding;
    }

    private boolean claimMatchesToolResults(ExpertFinding finding, List<String> refs,
                                            Map<String, EvidenceLedger.Observation> observed) {
        StatusClaim claim = StatusClaim.from(finding.conclusion());
        if (claim == StatusClaim.NONE) return true;

        // Bind each claimed status to its own cited entity: "D1 offline while
        // D2 online" is valid even though one global enum contradicts one of
        // the two observations. Entities are the device identifiers that also
        // appear in the cited results.
        Map<String, Set<String>> statusesByEntity = statusesByEntity(refs, observed);
        Map<String, StatusClaim> entityClaims = entityClaims(finding.conclusion(), statusesByEntity.keySet());
        if (!entityClaims.isEmpty()) {
            for (var entry : entityClaims.entrySet()) {
                Set<String> actual = statusesByEntity.get(entry.getKey());
                if (actual == null || actual.isEmpty() || !actual.stream().allMatch(entry.getValue()::matches)) {
                    return false;
                }
            }
            return true;
        }

        // No per-entity binding was possible — keep the conservative global check.
        boolean foundStatus = false;
        for (String ref : refs) {
            for (String status : statuses(observed.get(ref).result())) {
                foundStatus = true;
                if (!claim.matches(status)) return false;
            }
        }
        return foundStatus;
    }

    /** Maps each device identifier found in cited results to its observed status values. */
    private Map<String, Set<String>> statusesByEntity(List<String> refs,
                                                      Map<String, EvidenceLedger.Observation> observed) {
        Map<String, Set<String>> byEntity = new java.util.LinkedHashMap<>();
        for (String ref : refs) {
            try {
                JsonNode root = JSON.readTree(observed.get(ref).result());
                collectEntityStatuses(root, byEntity);
            } catch (Exception ignored) {
                // Non-JSON results cannot establish entity-bound claims.
            }
        }
        return byEntity;
    }

    private void collectEntityStatuses(JsonNode node, Map<String, Set<String>> byEntity) {
        if (node == null) return;
        if (node.isObject()) {
            String entityId = null;
            String status = null;
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if (("deviceid".equals(key) || "device_id".equals(key)) && value.isTextual()) {
                    entityId = value.asText().trim();
                } else if ("status".equals(key) && value.isTextual()) {
                    status = value.asText().trim().toUpperCase(Locale.ROOT);
                }
                collectEntityStatuses(value, byEntity);
            }
            if (entityId != null && status != null && !status.isBlank()) {
                byEntity.computeIfAbsent(entityId, ignored -> new java.util.LinkedHashSet<>()).add(status);
            }
        } else if (node.isArray()) {
            node.forEach(value -> collectEntityStatuses(value, byEntity));
        }
    }

    /**
     * Extracts per-entity status claims: each clause of the conclusion that
     * names a known entity and carries a status keyword yields entity→claim.
     */
    private Map<String, StatusClaim> entityClaims(String conclusion, Set<String> entities) {
        Map<String, StatusClaim> claims = new java.util.LinkedHashMap<>();
        for (String clause : conclusion.split("；|;|。|\\n|，|,|while|而")) {
            String normalized = clause.toLowerCase(Locale.ROOT);
            for (String entity : entities) {
                if (!normalized.contains(entity.toLowerCase(Locale.ROOT))) continue;
                StatusClaim claim = StatusClaim.from(clause);
                if (claim != StatusClaim.NONE) claims.putIfAbsent(entity, claim);
            }
        }
        return claims;
    }

    private static boolean isUsableObservation(EvidenceLedger.Observation observation) {
        if (observation == null || observation.result().isBlank()) return false;
        String raw = observation.result().strip();
        try {
            JsonNode root = JSON.readTree(raw);
            return root != null && !root.isNull() && !root.isMissingNode() && !containsError(root);
        } catch (Exception notJson) {
            String lowered = raw.toLowerCase(Locale.ROOT);
            return !(lowered.startsWith("error") || lowered.startsWith("failed")
                    || lowered.startsWith("failure") || lowered.startsWith("错误")
                    || lowered.startsWith("失败"));
        }
    }

    private static boolean containsError(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                if (("error".equals(key) || "errors".equals(key)) && hasErrorValue(value)) return true;
                if ("success".equals(key) && value.isBoolean() && !value.booleanValue()) return true;
                if ("status".equals(key) && value.isTextual()
                        && Set.of("ERROR", "FAILED", "FAILURE").contains(
                                value.asText().strip().toUpperCase(Locale.ROOT))) return true;
                if (containsError(value)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) if (containsError(value)) return true;
        }
        return false;
    }

    private static boolean hasErrorValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isArray() || value.isObject()) return !value.isEmpty();
        if (value.isTextual()) return !value.asText().isBlank();
        if (value.isBoolean()) return value.booleanValue();
        return true;
    }

    private static String groundedConclusion(List<String> refs,
                                             Map<String, EvidenceLedger.Observation> observed) {
        return refs.stream()
                .map(ref -> "已验证工具结果[" + ref + "]: " + canonicalResult(observed.get(ref).result()))
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private static String canonicalResult(String result) {
        String raw = result.strip();
        try {
            return JSON.writeValueAsString(JSON.readTree(raw));
        } catch (Exception notJson) {
            return raw;
        }
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
