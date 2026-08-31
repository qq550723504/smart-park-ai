package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.agent.ModelOutputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SupervisorPlanner {
    private static final Logger log = LoggerFactory.getLogger(SupervisorPlanner.class);
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
    private static final Set<String> PLAN_FIELDS = Set.of(
            "normalizedQuestion", "selectedDomains", "assignments", "selectionReason");
    private static final Set<String> ASSIGNMENT_FIELDS = Set.of("domain", "assignment");
    private final SupervisorPlanValidator validator;

    public SupervisorPlanner() { this(new SupervisorPlanValidator()); }

    public SupervisorPlanner(SupervisorPlanValidator validator) {
        this.validator = validator;
    }

    public SupervisorPlan parseAndValidate(String question, String modelJson) {
        String normalizedQuestion = normalize(question);
        JsonNode root = parseObject(modelJson);
        if (!root.fieldNames().hasNext()) {
            throw reject(PlannerRejection.EMPTY_OBJECT, "planner response must not be empty");
        }
        rejectUnknownFields(root, PLAN_FIELDS);
        JsonNode selected = root.get("selectedDomains");
        JsonNode assignments = root.get("assignments");
        if (!root.has("normalizedQuestion") || !root.has("selectionReason") || selected == null || assignments == null) {
            throw reject(PlannerRejection.MISSING_REQUIRED_FIELD, "planner response is missing required fields");
        }
        String providerQuestion = requireText(root, "normalizedQuestion", PlannerRejection.NORMALIZED_QUESTION_TYPE);
        if (!normalize(providerQuestion).equals(normalizedQuestion)) {
            throw reject(PlannerRejection.QUESTION_MISMATCH,
                    "normalizedQuestion must exactly match the normalized input question");
        }
        EnumSet<ExpertDomain> modelDomains = EnumSet.noneOf(ExpertDomain.class);
        if (!selected.isArray()) {
            throw reject(PlannerRejection.SELECTED_DOMAINS_TYPE, "selectedDomains must be an array");
        }
        for (JsonNode value : selected) {
            if (!value.isTextual()) {
                throw reject(PlannerRejection.SELECTED_DOMAIN_TYPE,
                        "selectedDomains entries must be strings");
            }
            if (!modelDomains.add(parseDomain(value.textValue(), value, normalizedQuestion))) {
                throw reject(PlannerRejection.COVERAGE,
                        "selectedDomains must not contain duplicate domains");
            }
        }
        if (!assignments.isArray()) {
            throw reject(PlannerRejection.ASSIGNMENTS_TYPE, "assignments must be an array");
        }
        EnumMap<ExpertDomain, String> modelAssignments = new EnumMap<>(ExpertDomain.class);
        for (JsonNode value : assignments) {
            if (!value.isObject()) {
                throw reject(PlannerRejection.ASSIGNMENT_ENTRY_TYPE,
                        "assignments entries must be objects");
            }
            rejectUnknownFields(value, ASSIGNMENT_FIELDS);
            JsonNode domainValue = value.get("domain");
            if (domainValue == null || !domainValue.isTextual()) {
                throw reject(PlannerRejection.ASSIGNMENT_DOMAIN_TYPE,
                        "assignment domain must be a string");
            }
            ExpertDomain domain = parseDomain(domainValue.textValue(), domainValue, normalizedQuestion);
            String assignment = requireTextValue(value.get("assignment"), "assignment",
                    PlannerRejection.ASSIGNMENT_TYPE);
            requireExactEntityScope(normalizedQuestion, assignment, domain);
            if (modelAssignments.put(domain, assignment) != null) {
                throw reject(PlannerRejection.COVERAGE,
                        "assignments must not contain duplicate domains");
            }
        }
        if (!modelAssignments.keySet().equals(modelDomains)) {
            throw reject(PlannerRejection.COVERAGE, "assignments must exactly cover selectedDomains");
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
                requireText(root, "selectionReason", PlannerRejection.SELECTION_REASON_TYPE));
        return validator.validate(plan);
    }

    private static JsonNode parseObject(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) {
                throw reject(PlannerRejection.NON_OBJECT, "planner response must be a JSON object");
            }
            return node;
        } catch (Exception ex) {
            if (ex instanceof ModelOutputException e) throw e;
            throw reject(PlannerRejection.MALFORMED_JSON, "planner response was not valid JSON", ex);
        }
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("question must not be blank");
        return text.trim();
    }

    private static String requireText(JsonNode root, String field, PlannerRejection rejection) {
        return requireTextValue(root.get(field), field, rejection);
    }

    private static ModelOutputException reject(PlannerRejection rejection, String message) {
        log.warn(rejection.name());
        return new ModelOutputException(message);
    }

    private static ModelOutputException reject(PlannerRejection rejection, String message, Exception cause) {
        log.warn(rejection.name());
        return new ModelOutputException(message, cause);
    }

    private enum PlannerRejection {
        MALFORMED_JSON,
        NON_OBJECT,
        EMPTY_OBJECT,
        MISSING_REQUIRED_FIELD,
        UNKNOWN_FIELD,
        NORMALIZED_QUESTION_TYPE,
        QUESTION_MISMATCH,
        SELECTED_DOMAINS_TYPE,
        SELECTED_DOMAIN_TYPE,
        DOMAIN,
        ASSIGNMENTS_TYPE,
        ASSIGNMENT_ENTRY_TYPE,
        ASSIGNMENT_DOMAIN_TYPE,
        ASSIGNMENT_TYPE,
        ASSIGNMENT_SCOPE,
        COVERAGE,
        SELECTION_REASON_TYPE
    }

    private static String requireTextValue(JsonNode value, String description, PlannerRejection rejection) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw reject(rejection, description + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowedFields) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw reject(PlannerRejection.UNKNOWN_FIELD, "planner response contains an unknown field");
            }
        });
    }

    private static void requireExactEntityScope(String question, String assignment, ExpertDomain domain) {
        Set<String> requiredIdentifiers = entityIdentifiers(question);
        Set<String> assignmentIdentifiers = entityIdentifiers(assignment);
        if (assignmentIdentifiers.equals(requiredIdentifiers)) return;

        Set<String> missing = new LinkedHashSet<>(requiredIdentifiers);
        missing.removeAll(assignmentIdentifiers);
        Set<String> unexpected = new LinkedHashSet<>(assignmentIdentifiers);
        unexpected.removeAll(requiredIdentifiers);
        throw reject(PlannerRejection.ASSIGNMENT_SCOPE, "assignment for " + domain
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
            throw reject(PlannerRejection.DOMAIN, "ambiguous expert domain: " + original);
        }
        if (ENERGY_DEVICE_ID.matcher(normalized).matches()) return ExpertDomain.ENERGY;
        if (SECURITY_EVENT_ID.matcher(normalized).matches()) return ExpertDomain.SECURITY;
        if (DEVICE_ID.matcher(normalized).matches()) return ExpertDomain.DEVICE;
        try { return ExpertDomain.valueOf(normalized.toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { throw reject(PlannerRejection.DOMAIN, "unknown expert domain: " + original); }
    }
}
