package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
            Synthesis synthesis = new Synthesis(
                    FindingStatus.valueOf(required(root, "status").toUpperCase()),
                    required(root, "conclusion"), strings(root.get("evidenceRefs")),
                    root.path("confidence").asDouble(Double.NaN), strings(root.get("uncertainties")));
            return validator.validate(synthesis, safeFindings);
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
}
