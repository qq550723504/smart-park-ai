package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.security.SecurityQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertDiagnosisAgent {

    private static final BeanOutputConverter<DiagnosisModelOutput> OUTPUT_CONVERTER =
            AlertStructuredOutputSupport.converter(DiagnosisModelOutput.class);
    private static final ObjectReader OUTPUT_READER =
            AlertStructuredOutputSupport.reader(DiagnosisModelOutput.class);

    private final ChatClient chatClient;
    private final ToolCallback[] toolCallbacks;

    public AlertDiagnosisAgent(
            ChatModel chatModel,
            DeviceQueryTool deviceQueryTool,
            AlertQueryTool alertQueryTool,
            WorkOrderTool workOrderTool,
            ParkKnowledgeTool parkKnowledgeTool) {
        this(chatModel, deviceQueryTool, alertQueryTool, workOrderTool, parkKnowledgeTool, null, null);
    }

    public AlertDiagnosisAgent(
            ChatModel chatModel,
            DeviceQueryTool deviceQueryTool,
            AlertQueryTool alertQueryTool,
            WorkOrderTool workOrderTool,
            ParkKnowledgeTool parkKnowledgeTool,
            EnergyQueryTool energyQueryTool) {
        this(chatModel, deviceQueryTool, alertQueryTool, workOrderTool, parkKnowledgeTool, energyQueryTool, null);
    }

    @Autowired
    public AlertDiagnosisAgent(
            ChatModel chatModel,
            DeviceQueryTool deviceQueryTool,
            AlertQueryTool alertQueryTool,
            WorkOrderTool workOrderTool,
            ParkKnowledgeTool parkKnowledgeTool,
            EnergyQueryTool energyQueryTool,
            SecurityQueryTool securityQueryTool) {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(deviceQueryTool, "deviceQueryTool");
        Objects.requireNonNull(alertQueryTool, "alertQueryTool");
        Objects.requireNonNull(workOrderTool, "workOrderTool");
        Objects.requireNonNull(parkKnowledgeTool, "parkKnowledgeTool");
        this.chatClient = ChatClient.builder(chatModel).build();
        List<Object> readOnlyTools = new ArrayList<>(List.of(
                deviceQueryTool, alertQueryTool, parkKnowledgeTool));
        if (energyQueryTool != null) {
            readOnlyTools.add(energyQueryTool);
        }
        if (securityQueryTool != null) {
            readOnlyTools.add(securityQueryTool);
        }
        Stream<ToolCallback> parkTools = Stream.of(ToolCallbacks.from(readOnlyTools.toArray()));
        this.toolCallbacks = Stream.concat(
                        parkTools,
                        Stream.of(workOrderTool.diagnosisCallbacks()))
                .flatMap(Stream::of)
                .toArray(ToolCallback[]::new);
    }

    public Diagnosis diagnose(Alert alert, ParkContext context, List<KnowledgeDocument> documents) {
        return diagnose(alert, context, documents, ignored -> { }, ignored -> { });
    }

    public Diagnosis diagnose(
            Alert alert,
            ParkContext context,
            List<KnowledgeDocument> documents,
            Consumer<String> toolAuditor) {
        return diagnose(alert, context, documents, toolAuditor, ignored -> { });
    }

    public Diagnosis diagnose(
            Alert alert,
            ParkContext context,
            List<KnowledgeDocument> documents,
            Consumer<String> toolAuditor,
            Consumer<AlertModelFailureStage> failureObserver) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(context, "context");
        List<KnowledgeDocument> safeDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        Consumer<String> requiredToolAuditor = Objects.requireNonNull(toolAuditor, "toolAuditor");
        Consumer<AlertModelFailureStage> requiredFailureObserver = Objects.requireNonNull(
                failureObserver, "failureObserver");

        Prompt prompt = new Prompt(
                new SystemMessage(PromptCatalog.diagnosisSystemPrompt(toolNames()) + OUTPUT_CONVERTER.getFormat()),
                new UserMessage(PromptCatalog.diagnosisUserPrompt(alert, context, safeDocuments)));
        try {
            return createDiagnosis(callModel(prompt, requiredToolAuditor), alert, safeDocuments);
        }
        catch (ModelOutputException firstFailure) {
            Prompt retry = new Prompt(
                    new SystemMessage(PromptCatalog.diagnosisSystemPrompt(toolNames())
                            + OUTPUT_CONVERTER.getFormat()
                            + PromptCatalog.strictRetryInstruction()),
                    new UserMessage(PromptCatalog.diagnosisUserPrompt(alert, context, safeDocuments)));
            try {
                return createDiagnosis(callModel(retry, requiredToolAuditor), alert, safeDocuments);
            }
            catch (ModelOutputException terminalFailure) {
                reportBoundaryFailure(requiredFailureObserver, terminalFailure);
                throw terminalFailure;
            }
            catch (RuntimeException exception) {
                requiredFailureObserver.accept(AlertModelFailureStage.PROVIDER_CALL);
                throw exception;
            }
        }
        catch (RuntimeException exception) {
            requiredFailureObserver.accept(AlertModelFailureStage.PROVIDER_CALL);
            throw exception;
        }
    }

    private DiagnosisModelOutput callModel(Prompt prompt, Consumer<String> toolAuditor) {
        String content = chatClient.prompt(prompt)
                .options(AlertStructuredOutputSupport.providerOptions("alert_diagnosis", OUTPUT_CONVERTER))
                .toolCallbacks(auditedToolCallbacks(toolAuditor))
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new ModelOutputException("diagnosis response was empty", AlertModelFailureStage.EMPTY_RESPONSE);
        }
        try {
            return AlertStructuredOutputSupport.convert(OUTPUT_READER, content, "diagnosis");
        }
        catch (ModelOutputException exception) {
            throw new ModelOutputException(exception.getMessage(), AlertModelFailureStage.DIAGNOSIS_PARSE);
        }
    }

    private Diagnosis createDiagnosis(
            DiagnosisModelOutput output,
            Alert alert,
            List<KnowledgeDocument> safeDocuments) {
        List<String> evidence = new ArrayList<>(output.evidence());
        if (safeDocuments.isEmpty() && evidence.stream().noneMatch(item -> item.contains("INSUFFICIENT_EVIDENCE"))) {
            evidence.add(PromptCatalog.INSUFFICIENT_EVIDENCE_MARKER);
        }
        Diagnosis diagnosis = new Diagnosis(
                UUID.randomUUID().toString(),
                alert.id(),
                alert.deviceId(),
                output.riskLevel(),
                output.rootCause(),
                output.summary(),
                List.copyOf(evidence),
                output.recommendedAction(),
                output.confidence(),
                Instant.now());

        return diagnosis;
    }

    public ToolCallback[] toolCallbacks() {
        return toolCallbacks.clone();
    }

    private ToolCallback[] auditedToolCallbacks(Consumer<String> auditor) {
        return Stream.of(toolCallbacks)
                .map(callback -> new AuditedToolCallback(callback, auditor))
                .toArray(ToolCallback[]::new);
    }

    private static void reportBoundaryFailure(
            Consumer<AlertModelFailureStage> failureObserver,
            ModelOutputException failure) {
        AlertModelFailureStage stage = failure.failureStage();
        failureObserver.accept(stage == null ? AlertModelFailureStage.DIAGNOSIS_PARSE : stage);
    }

    private List<String> toolNames() {
        return Stream.of(toolCallbacks)
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .distinct()
                .toList();
    }

    private record DiagnosisModelOutput(
            RiskLevel riskLevel,
            String rootCause,
            String summary,
            List<String> evidence,
            String recommendedAction,
            double confidence) {

        private DiagnosisModelOutput {
            riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
            rootCause = requireText(rootCause, "rootCause");
            summary = requireText(summary, "summary");
            recommendedAction = requireText(recommendedAction, "recommendedAction");
            evidence = validatedEvidence(evidence);
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must be a non-empty string");
            }
            return value.trim();
        }

        private static List<String> validatedEvidence(List<String> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("evidence must be non-empty");
            }
            return values.stream()
                    .map(value -> requireText(value, "evidence item"))
                    .toList();
        }
    }

    private record AuditedToolCallback(ToolCallback delegate, Consumer<String> auditor) implements ToolCallback {

        private AuditedToolCallback {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(auditor, "auditor");
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            auditor.accept(delegate.getToolDefinition().name());
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            auditor.accept(delegate.getToolDefinition().name());
            return delegate.call(toolInput, toolContext);
        }
    }
}
