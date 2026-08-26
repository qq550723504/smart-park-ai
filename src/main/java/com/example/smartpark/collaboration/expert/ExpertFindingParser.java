package com.example.smartpark.collaboration.expert;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Parses the common, deliberately narrow finding contract returned by an expert model. */
public final class ExpertFindingParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, ExpertDomain> DOMAIN_ALIASES = Map.of(
            "energy park", ExpertDomain.ENERGY,
            "device park", ExpertDomain.DEVICE,
            "security park", ExpertDomain.SECURITY);

    public ExpertFinding parse(String text, ExpertDomain expectedDomain) {
        Objects.requireNonNull(expectedDomain, "expectedDomain");
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("finding must be an object");
            ExpertDomain domain = parseDomain(required(root, "domain"));
            if (domain != expectedDomain) throw new IllegalArgumentException("finding domain does not match expert");
            FindingStatus status = FindingStatus.valueOf(required(root, "status").toUpperCase());
            List<String> refs = strings(root.get("evidenceRefs"));
            List<String> checks = strings(root.get("nextChecks"));
            double confidence = status == FindingStatus.SUPPORTED
                    ? root.path("confidence").asDouble(Double.NaN) : 0;
            return new ExpertFinding(domain, status, required(root, "conclusion"), refs, confidence, checks);
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException e && e.getMessage() != null) throw e;
            throw new IllegalArgumentException("invalid expert finding JSON", ex);
        }
    }

    private static String required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " must be non-empty");
        return value.asText().trim();
    }

    private static ExpertDomain parseDomain(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        ExpertDomain alias = DOMAIN_ALIASES.get(normalized);
        return alias == null ? ExpertDomain.valueOf(normalized.toUpperCase(Locale.ROOT)) : alias;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("finding list field must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("finding list values must be non-empty strings");
            values.add(item.asText().trim());
        }
        return List.copyOf(values);
    }
}
