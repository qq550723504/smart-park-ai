package com.example.smartpark.agent;

import com.example.smartpark.model.Alert;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.KnowledgeDocument;
import com.example.smartpark.model.ParkContext;
import com.example.smartpark.model.RiskLevel;
import com.example.smartpark.tool.AlertQueryTool;
import com.example.smartpark.tool.DeviceQueryTool;
import com.example.smartpark.tool.ParkKnowledgeTool;
import com.example.smartpark.tool.WorkOrderTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class AlertDiagnosisAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "id",
            "alertId",
            "deviceId",
            "riskLevel",
            "rootCause",
            "summary",
            "evidence",
            "recommendedAction",
            "diagnosedAt");

    private final ChatModel chatModel;
    private final ToolCallback[] toolCallbacks;

    public AlertDiagnosisAgent(
            ChatModel chatModel,
            DeviceQueryTool deviceQueryTool,
            AlertQueryTool alertQueryTool,
            WorkOrderTool workOrderTool,
            ParkKnowledgeTool parkKnowledgeTool) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(deviceQueryTool, "deviceQueryTool");
        Objects.requireNonNull(alertQueryTool, "alertQueryTool");
        Objects.requireNonNull(workOrderTool, "workOrderTool");
        Objects.requireNonNull(parkKnowledgeTool, "parkKnowledgeTool");
        this.toolCallbacks = Stream.concat(
                        Stream.of(ToolCallbacks.from(deviceQueryTool, alertQueryTool, parkKnowledgeTool)),
                        Stream.of(workOrderTool.diagnosisCallbacks()))
                .flatMap(Stream::of)
                .toArray(ToolCallback[]::new);
    }

    public Diagnosis diagnose(Alert alert, ParkContext context, List<KnowledgeDocument> documents) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(context, "context");
        List<KnowledgeDocument> safeDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));

        Prompt prompt = new Prompt(
                new SystemMessage(PromptCatalog.diagnosisSystemPrompt(toolNames())),
                new UserMessage(PromptCatalog.diagnosisUserPrompt(alert, context, safeDocuments)));
        String text = extractText(chatModel.call(prompt), "diagnosis");
        JsonNode root = parseObject(text, "diagnosis");
        validateFields(root, EXPECTED_FIELDS, "diagnosis");

        Diagnosis diagnosis = new Diagnosis(
                requireText(root, "id", "diagnosis"),
                requireMatchingId(root, "alertId", alert.id(), "diagnosis"),
                requireMatchingId(root, "deviceId", alert.deviceId(), "diagnosis"),
                RiskLevel.valueOf(requireText(root, "riskLevel", "diagnosis")),
                requireText(root, "rootCause", "diagnosis"),
                requireText(root, "summary", "diagnosis"),
                requireEvidence(root.get("evidence"), "diagnosis"),
                requireText(root, "recommendedAction", "diagnosis"),
                requireInstant(root, "diagnosedAt", "diagnosis"));

        if (safeDocuments.isEmpty() && diagnosis.evidence().stream().noneMatch(item -> item.contains("INSUFFICIENT_EVIDENCE"))) {
            throw new IllegalStateException("diagnosis must acknowledge insufficient evidence when no knowledge documents are available");
        }

        return diagnosis;
    }

    public ToolCallback[] toolCallbacks() {
        return toolCallbacks.clone();
    }

    private List<String> toolNames() {
        return Stream.of(toolCallbacks)
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .distinct()
                .toList();
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

    private static String requireMatchingId(JsonNode root, String fieldName, String expectedValue, String context) {
        String actualValue = requireText(root, fieldName, context);
        if (!expectedValue.equals(actualValue)) {
            throw new IllegalStateException(context + " output field '" + fieldName + "' must match " + expectedValue);
        }
        return actualValue;
    }

    private static List<String> requireEvidence(JsonNode node, String context) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(context + " output field 'evidence' must be a non-empty array");
        }
        List<String> evidence = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalStateException(context + " evidence items must be non-empty strings");
            }
            evidence.add(item.textValue().trim());
        });
        return List.copyOf(evidence);
    }

    private static Instant requireInstant(JsonNode root, String fieldName, String context) {
        String value = requireText(root, fieldName, context);
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            throw new IllegalStateException(context + " output field '" + fieldName + "' must be an ISO-8601 instant", ex);
        }
    }
}
