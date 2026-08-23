package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertTriageAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> EXPECTED_FIELDS = Set.of("category", "priority", "riskLevel", "confidence");

    private final ChatModel chatModel;

    public AlertTriageAgent(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    public AlertClassificationResult classify(Alert alert) {
        Objects.requireNonNull(alert, "alert");
        Prompt prompt = new Prompt(
                new SystemMessage(PromptCatalog.triageSystemPrompt()),
                new UserMessage(PromptCatalog.triageUserPrompt(alert)));
        String text = extractText(chatModel.call(prompt), "triage");
        JsonNode root = parseObject(text, "triage");
        validateFields(root, EXPECTED_FIELDS, "triage");

        String category = requireText(root, "category", "triage");
        String priority = requireText(root, "priority", "triage");
        String riskLevel = requireText(root, "riskLevel", "triage");
        JsonNode confidenceNode = root.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            throw new IllegalStateException("triage output must contain a numeric confidence");
        }
        double confidence = confidenceNode.doubleValue();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalStateException("triage output field 'confidence' must be between 0 and 1");
        }

        return new AlertClassificationResult(
                parseEnum(AlertClassification.class, category, "category", "triage"),
                parseEnum(AlertPriority.class, priority, "priority", "triage"),
                parseEnum(RiskLevel.class, riskLevel, "riskLevel", "triage"),
                confidence);
    }

    private static String extractText(ChatResponse response, String context) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException(context + " response was empty");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(context + " response text was blank");
        }
        return text;
    }

    private static JsonNode parseObject(String text, String context) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(text);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException(context + " response must be a JSON object");
            }
            return root;
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(context + " response was not valid JSON", ex);
        }
    }

    private static void validateFields(JsonNode root, Set<String> expectedFields, String context) {
        Set<String> actualFields = new LinkedHashSet<>();
        Iterator<String> fieldNames = root.fieldNames();
        fieldNames.forEachRemaining(actualFields::add);
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalStateException(context + " response fields did not match expected shape: " + actualFields);
        }
    }

    private static String requireText(JsonNode root, String fieldName, String context) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(context + " output field '" + fieldName + "' must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, String fieldName, String context) {
        try {
            return Enum.valueOf(enumType, value);
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException(context + " output field '" + fieldName + "' must be one of " + java.util.List.of(enumType.getEnumConstants()), ex);
        }
    }

    public enum AlertPriority {
        LOW,
        MEDIUM,
        HIGH
    }

    public record AlertClassificationResult(
            AlertClassification category,
            AlertPriority priority,
            RiskLevel riskLevel,
            double confidence) {

        public AlertClassificationResult {
            category = Objects.requireNonNull(category, "category");
            priority = Objects.requireNonNull(priority, "priority");
            riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }
    }
}
