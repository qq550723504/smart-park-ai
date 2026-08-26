package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Supervisor synthesis is deliberately tool-free and can only consume validated findings. */
public final class SupervisorSynthesizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SynthesisValidator validator;

    public SupervisorSynthesizer() { this(new SynthesisValidator()); }

    public SupervisorSynthesizer(SynthesisValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Synthesis parseAndValidate(String modelJson, SupervisorPlan plan, List<ExpertFinding> findings) {
        Objects.requireNonNull(plan, "plan");
        List<ExpertFinding> safeFindings = List.copyOf(findings);
        if (!safeFindings.stream().map(ExpertFinding::domain).allMatch(plan.selectedDomains()::contains)) {
            throw new IllegalArgumentException("findings contain an unselected domain");
        }
        try {
            JsonNode root = JSON.readTree(modelJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("synthesis must be a JSON object");
            FindingStatus status = FindingStatus.valueOf(required(root, "status").toUpperCase());
            Set<ExpertDomain> modelSelectedDomains = domains(root.get("selectedDomains"));
            if (!plan.selectedDomains().containsAll(modelSelectedDomains)) {
                throw new IllegalArgumentException("synthesis selected a domain outside the supervisor plan");
            }
            Set<ExpertDomain> selectedDomains = status == FindingStatus.SUPPORTED
                    ? modelSelectedDomains : Set.of();
            double modelConfidence = root.path("confidence").asDouble(Double.NaN);
            if (!Double.isFinite(modelConfidence) || modelConfidence < 0 || modelConfidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            String conclusion = deterministicConclusion(status, selectedDomains, safeFindings);
            double derivedConfidence = status == FindingStatus.SUPPORTED
                    ? safeFindings.stream()
                    .filter(finding -> selectedDomains.contains(finding.domain()))
                    .mapToDouble(ExpertFinding::confidence)
                    .min()
                    .orElse(0.0)
                    : 0.0;
            List<String> uncertainties = strings(root.get("uncertainties"));
            if (uncertainties.isEmpty() && safeFindings.stream()
                    .anyMatch(finding -> finding.status() != FindingStatus.SUPPORTED)) {
                uncertainties = safeFindings.stream()
                        .filter(finding -> finding.status() != FindingStatus.SUPPORTED)
                        .sorted(Comparator.comparing(ExpertFinding::domain))
                        .map(finding -> finding.domain() + ": " + finding.conclusion())
                        .toList();
            }
            Synthesis synthesis = new Synthesis(
                    status, conclusion, status == FindingStatus.SUPPORTED ? strings(root.get("evidenceRefs")) : List.of(),
                    derivedConfidence, uncertainties);
            return validator.validate(synthesis, safeFindings, selectedDomains);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid synthesis JSON", ex);
        }
    }

    private static String required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " must be non-empty");
        return value.asText().trim();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("synthesis list field must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("synthesis lists require non-empty strings");
            values.add(item.asText().trim());
        }
        return List.copyOf(values);
    }

    private static Set<ExpertDomain> domains(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("selectedDomains must be an array");
        }
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("selectedDomains require non-empty strings");
            }
            ExpertDomain domain = ExpertDomain.valueOf(item.asText().trim().toUpperCase());
            if (!domains.add(domain)) {
                throw new IllegalArgumentException("selectedDomains must not contain duplicates");
            }
        }
        return Set.copyOf(domains);
    }

    private static String deterministicConclusion(FindingStatus status,
                                                  Set<ExpertDomain> selectedDomains,
                                                  List<ExpertFinding> findings) {
        String conclusion = findings.stream()
                .filter(finding -> selectedDomains.contains(finding.domain()))
                .filter(finding -> finding.status() == FindingStatus.SUPPORTED)
                .sorted(Comparator.comparing(ExpertFinding::domain))
                .map(ExpertFinding::conclusion)
                .collect(Collectors.joining("；"));
        if (!conclusion.isBlank()) {
            return conclusion;
        }
        return status == FindingStatus.FAILED ? "专家协作失败" : "没有可验证的专家结论";
    }
}
