package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.agent.ModelOutputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SupervisorPlanner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENERGY_DEVICE_ID = Pattern.compile("DEV-ENERGY-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_ID = Pattern.compile("DEV-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ENERGY_DEVICE_ID = Pattern.compile("DEV-(?!ENERGY-)[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECURITY_EVENT_ID = Pattern.compile("SEC-[A-Z0-9-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONCRETE_ENTITY_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])(?:[A-Z][A-Z0-9]{0,15}(?:-[A-Z0-9]+)+|[A-Z]{1,8}\\d+)(?![A-Z0-9_-])");
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
        String providerQuestion = requireText(root, "normalizedQuestion");
        if (!normalize(providerQuestion).equals(normalizedQuestion)) {
            throw new ModelOutputException("normalizedQuestion must exactly match the normalized input question");
        }
        EnumSet<ExpertDomain> modelDomains = EnumSet.noneOf(ExpertDomain.class);
        if (!selected.isArray()) throw new ModelOutputException("selectedDomains must be an array");
        for (JsonNode value : selected) {
            modelDomains.add(parseDomain(value.asText(), value, normalizedQuestion));
        }
        if (!assignments.isObject()) throw new ModelOutputException("assignments must be an object");
        EnumMap<ExpertDomain, String> modelAssignments = new EnumMap<>(ExpertDomain.class);
        Iterator<Map.Entry<String, JsonNode>> fields = assignments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            ExpertDomain domain = parseDomain(field.getKey(), field.getKey(), normalizedQuestion);
            String assignment = requireTextValue(field.getValue(), "assignment for " + field.getKey());
            requireExactEntityScope(normalizedQuestion, assignment, domain);
            modelAssignments.put(domain, assignment);
        }
        if (!modelAssignments.keySet().equals(modelDomains)) {
            throw new ModelOutputException("assignments must exactly cover selectedDomains");
        }

        // The provider response is mandatory structured confirmation and its
        // explanation is retained, but routing has exactly one authority: the
        // server-owned deterministic classifier. Provider grouping may differ
        // without dropping or adding an expert branch in the executable plan.
        Set<ExpertDomain> requiredDomains = validator.expectedDomains(normalizedQuestion);
        if (requiredDomains.isEmpty()) {
            throw new SupervisorPlanValidator.SupervisorPlanValidationException(
                    "question is ambiguous or outside expert collaboration scope");
        }
        EnumMap<ExpertDomain, String> canonicalAssignments = new EnumMap<>(ExpertDomain.class);
        requiredDomains.forEach(domain -> canonicalAssignments.put(domain, normalizedQuestion));
        SupervisorPlan plan = new SupervisorPlan(
                normalizedQuestion, requiredDomains, canonicalAssignments,
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
        return requireTextValue(root.get(field), field);
    }

    private static String requireTextValue(JsonNode value, String description) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ModelOutputException(description + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    private static void requireExactEntityScope(String question, String assignment, ExpertDomain domain) {
        Set<String> requiredIdentifiers = entityIdentifiers(question);
        Set<String> assignmentIdentifiers = entityIdentifiers(assignment);
        if (assignmentIdentifiers.equals(requiredIdentifiers)) return;

        Set<String> missing = new LinkedHashSet<>(requiredIdentifiers);
        missing.removeAll(assignmentIdentifiers);
        Set<String> unexpected = new LinkedHashSet<>(assignmentIdentifiers);
        unexpected.removeAll(requiredIdentifiers);
        throw new ModelOutputException("assignment for " + domain
                + " must preserve the exact input entity scope; missing=" + missing
                + ", unexpected=" + unexpected);
    }

    private static Set<String> entityIdentifiers(String text) {
        Set<String> identifiers = new LinkedHashSet<>();
        var matcher = CONCRETE_ENTITY_IDENTIFIER.matcher(text);
        while (matcher.find()) {
            identifiers.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(identifiers);
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
