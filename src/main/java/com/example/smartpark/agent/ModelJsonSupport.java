package com.example.smartpark.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ModelJsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private ModelJsonSupport() {
    }

    static JsonNode parseObject(String text, String context) {
        String candidate = extractObjectCandidate(text, context);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(candidate);
            if (root == null || !root.isObject()) {
                throw new ModelOutputException(context + " response must be a JSON object");
            }
            return root;
        }
        catch (JsonProcessingException ex) {
            throw new ModelOutputException(context + " response was not valid JSON", ex);
        }
    }

    private static String extractObjectCandidate(String text, String context) {
        if (text == null || text.isBlank()) {
            throw new ModelOutputException(context + " response text was blank");
        }
        String candidate = text.trim();
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            int firstLineBreak = candidate.indexOf('\n');
            if (firstLineBreak >= 0) {
                candidate = candidate.substring(firstLineBreak + 1, candidate.length() - 3).trim();
            }
        }
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            candidate = candidate.substring(objectStart, objectEnd + 1);
        }
        return candidate;
    }
}
