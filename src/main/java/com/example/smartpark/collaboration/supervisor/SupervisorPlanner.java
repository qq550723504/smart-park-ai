package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.agent.ModelOutputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SupervisorPlanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENERGY_DEVICE_ID = Pattern.compile("DEV-ENERGY-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_ID = Pattern.compile("DEV-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ENERGY_DEVICE_ID = Pattern.compile("DEV-(?!ENERGY-)[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECURITY_EVENT_ID = Pattern.compile("SEC-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Map<String, ExpertDomain> DOMAIN_ALIASES = Map.ofEntries(
            Map.entry("energy", ExpertDomain.ENERGY),
            Map.entry("energetics", ExpertDomain.ENERGY),
            Map.entry("power_consumption", ExpertDomain.ENERGY),
            Map.entry("consumption", ExpertDomain.ENERGY),
            Map.entry("能耗", ExpertDomain.ENERGY),
            Map.entry("用电", ExpertDomain.ENERGY),
            Map.entry("电量", ExpertDomain.ENERGY),
            Map.entry("device", ExpertDomain.DEVICE),
            Map.entry("devices", ExpertDomain.DEVICE),
            Map.entry("equipment", ExpertDomain.DEVICE),
            Map.entry("设备", ExpertDomain.DEVICE),
            Map.entry("设备状态", ExpertDomain.DEVICE),
            Map.entry("security", ExpertDomain.SECURITY),
            Map.entry("access", ExpertDomain.SECURITY),
            Map.entry("door", ExpertDomain.SECURITY),
            Map.entry("安防", ExpertDomain.SECURITY),
            Map.entry("门禁", ExpertDomain.SECURITY));
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
        requireText(root, "normalizedQuestion");
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        if (!selected.isArray()) throw new ModelOutputException("selectedDomains must be an array");
        for (JsonNode value : selected) {
            domains.add(parseDomain(value.asText(), value, normalizedQuestion));
        }
        if (!assignments.isObject()) throw new ModelOutputException("assignments must be an object");
        EnumMap<ExpertDomain, String> assignmentMap = new EnumMap<>(ExpertDomain.class);
        Iterator<Map.Entry<String, JsonNode>> fields = assignments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            assignmentMap.put(parseDomain(field.getKey(), field.getKey(), normalizedQuestion), field.getValue().asText());
        }
        if (!assignmentMap.keySet().equals(domains)) {
            throw new ModelOutputException("assignments must exactly cover selectedDomains");
        }
        // The model may choose domains and explain that choice, but it does not
        // own entity scope. Every expert receives the exact user question so a
        // generated assignment cannot replace D1 with D2 or drop another
        // concrete identifier.
        EnumMap<ExpertDomain, String> canonicalAssignments = new EnumMap<>(ExpertDomain.class);
        domains.forEach(domain -> canonicalAssignments.put(domain, normalizedQuestion));
        SupervisorPlan plan = new SupervisorPlan(
                normalizedQuestion, domains, canonicalAssignments,
                requireText(root, "selectionReason"));
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

    private static ExpertDomain parseDomain(String raw, Object original, String question) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        ExpertDomain domain = DOMAIN_ALIASES.get(normalized);
        if (domain != null) return domain;
        if ("power".equals(normalized)) {
            String questionText = question.toLowerCase(Locale.ROOT);
            boolean explicitEnergyContext = questionText.contains("energy")
                    || questionText.contains("能耗") || questionText.contains("用电")
                    || questionText.contains("电量") || questionText.contains("consumption");
            boolean explicitDeviceContext = NON_ENERGY_DEVICE_ID.matcher(questionText).find()
                    || questionText.contains("device") || questionText.contains("equipment")
                    || questionText.contains("设备");
            if (explicitEnergyContext && !explicitDeviceContext) {
                return ExpertDomain.ENERGY;
            }
            if (explicitDeviceContext) {
                return ExpertDomain.DEVICE;
            }
            if (questionText.contains("dev-energy-")) return ExpertDomain.ENERGY;
            throw new ModelOutputException("ambiguous expert domain: " + original);
        }
        if (ENERGY_DEVICE_ID.matcher(normalized).matches()) return ExpertDomain.ENERGY;
        if (SECURITY_EVENT_ID.matcher(normalized).matches()) return ExpertDomain.SECURITY;
        if (DEVICE_ID.matcher(normalized).matches()) return ExpertDomain.DEVICE;
        try { return ExpertDomain.valueOf(normalized.toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw new ModelOutputException("unknown expert domain: " + original); }
    }
}
