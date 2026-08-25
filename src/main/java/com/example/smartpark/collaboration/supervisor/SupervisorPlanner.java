package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.agent.ModelOutputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;

public final class SupervisorPlanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SupervisorPlanValidator validator;

    public SupervisorPlanner() { this(new SupervisorPlanValidator()); }

    public SupervisorPlanner(SupervisorPlanValidator validator) {
        this.validator = validator;
    }

    public SupervisorPlan parseAndValidate(String question, String modelJson) {
        String normalizedQuestion = normalize(question);
        JsonNode root = parseObject(modelJson);
        if (!root.fieldNames().hasNext()) throw new ModelOutputException("planner response must not be empty");
        JsonNode selected = root.get("selectedDomains");
        JsonNode assignments = root.get("assignments");
        if (!root.has("normalizedQuestion") || !root.has("selectionReason") || selected == null || assignments == null) {
            throw new ModelOutputException("planner response is missing required fields");
        }
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        if (!selected.isArray()) throw new ModelOutputException("selectedDomains must be an array");
        for (JsonNode value : selected) {
            try { domains.add(ExpertDomain.valueOf(value.asText().toUpperCase())); }
            catch (Exception ex) { throw new ModelOutputException("unknown expert domain: " + value); }
        }
        if (!assignments.isObject()) throw new ModelOutputException("assignments must be an object");
        EnumMap<ExpertDomain, String> assignmentMap = new EnumMap<>(ExpertDomain.class);
        Iterator<Map.Entry<String, JsonNode>> fields = assignments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            try { assignmentMap.put(ExpertDomain.valueOf(field.getKey().toUpperCase()), field.getValue().asText()); }
            catch (Exception ex) { throw new ModelOutputException("unknown assignment domain: " + field.getKey()); }
        }
        SupervisorPlan plan = new SupervisorPlan(
                requireText(root, "normalizedQuestion"), domains, assignmentMap,
                requireText(root, "selectionReason"));
        if (!plan.normalizedQuestion().equals(normalizedQuestion)) {
            plan = new SupervisorPlan(normalizedQuestion, plan.selectedDomains(), plan.assignments(), plan.selectionReason());
        }
        return validator.validate(plan);
    }

    private static JsonNode parseObject(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) throw new ModelOutputException("planner response must be a JSON object");
            return node;
        } catch (Exception ex) {
            if (ex instanceof ModelOutputException e) throw e;
            throw new ModelOutputException("planner response was not valid JSON", ex);
        }
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("question must not be blank");
        return text.trim();
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new ModelOutputException(field + " must be a non-empty string");
        return value.asText().trim();
    }
}
