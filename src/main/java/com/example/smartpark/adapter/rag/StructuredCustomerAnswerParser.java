package com.example.smartpark.adapter.rag;

import com.example.smartpark.agent.ModelOutputException;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StructuredCustomerAnswerParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("answer", "needsHuman", "reason", "citationIds");

    private StructuredCustomerAnswerParser() { }

    public static CustomerAnswer parse(String text, List<KnowledgeMatch> evidence) {
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) throw invalid("response must be a JSON object");
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) throw invalid("response fields did not match expected shape");
            if (!root.get("answer").isTextual() || root.get("answer").textValue().isBlank()) throw invalid("answer must be non-empty string");
            if (!root.get("needsHuman").isBoolean()) throw invalid("needsHuman must be boolean");
            if (!root.get("reason").isTextual()) throw invalid("reason must be string");
            if (!root.get("citationIds").isArray()) throw invalid("citationIds must be array");
            boolean needsHuman = root.get("needsHuman").booleanValue();
            CustomerAnswer.Reason reason = CustomerAnswer.Reason.valueOf(root.get("reason").textValue());
            if (!CustomerAnswerContract.modelReasons().contains(reason)) {
                throw invalid("reason is not model-selectable");
            }
            List<String> citations = new java.util.ArrayList<>();
            for (JsonNode citation : root.get("citationIds")) {
                if (!citation.isTextual() || citation.textValue().isBlank()) throw invalid("citationIds must contain strings");
                citations.add(citation.textValue());
            }
            if (needsHuman) {
                if (reason == CustomerAnswer.Reason.SUPPORTED) throw invalid("human transfer cannot be supported");
                if (!citations.isEmpty()) throw invalid("human transfers must not contain citations");
            } else {
                if (reason != CustomerAnswer.Reason.SUPPORTED) throw invalid("non-human answers must be supported");
                if (citations.isEmpty()) throw invalid("supported answers must cite at least one document");
            }
            if (new HashSet<>(citations).size() != citations.size()) {
                throw invalid("citationIds must not contain duplicates");
            }
            Set<String> allowed = evidence.stream().map(KnowledgeMatch::documentId).collect(java.util.stream.Collectors.toSet());
            if (!allowed.containsAll(citations)) throw invalid("citationIds must be retrieved document IDs");
            return new CustomerAnswer(root.get("answer").textValue(), needsHuman, reason, citations);
        } catch (Exception ex) {
            if (ex instanceof ModelOutputException) throw (ModelOutputException) ex;
            throw new ModelOutputException("customer answer response was invalid", ex);
        }
    }

    private static ModelOutputException invalid(String message) { return new ModelOutputException("customer answer " + message); }
}
