package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertTriageAgent {

    private static final BeanOutputConverter<TriageModelOutput> OUTPUT_CONVERTER =
            AlertStructuredOutputSupport.converter(TriageModelOutput.class);
    private static final ObjectReader OUTPUT_READER =
            AlertStructuredOutputSupport.reader(TriageModelOutput.class);

    private final ChatModel chatModel;

    public AlertTriageAgent(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    public AlertClassificationResult classify(Alert alert) {
        return classify(alert, ignored -> { });
    }

    public AlertClassificationResult classify(
            Alert alert,
            Consumer<AlertModelFailureStage> failureObserver) {
        Objects.requireNonNull(alert, "alert");
        Consumer<AlertModelFailureStage> requiredFailureObserver = Objects.requireNonNull(
                failureObserver, "failureObserver");
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(PromptCatalog.triageSystemPrompt() + OUTPUT_CONVERTER.getFormat()),
                        new UserMessage(PromptCatalog.triageUserPrompt(alert))),
                AlertStructuredOutputSupport.providerOptions("alert_triage", OUTPUT_CONVERTER));
        try {
            return classifyResponse(extractText(chatModel.call(prompt), "triage"));
        }
        catch (ModelOutputException firstFailure) {
            Prompt retry = new Prompt(
                    List.of(
                            new SystemMessage(PromptCatalog.triageSystemPrompt()
                                    + OUTPUT_CONVERTER.getFormat()
                                    + PromptCatalog.strictRetryInstruction()),
                            new UserMessage(PromptCatalog.triageUserPrompt(alert))),
                    AlertStructuredOutputSupport.providerOptions("alert_triage", OUTPUT_CONVERTER));
            try {
                return classifyResponse(extractText(chatModel.call(retry), "triage"));
            }
            catch (ModelOutputException terminalFailure) {
                reportBoundaryFailure(requiredFailureObserver, terminalFailure, AlertModelFailureStage.TRIAGE_PARSE);
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

    private AlertClassificationResult classifyResponse(String text) {
        try {
            TriageModelOutput output = AlertStructuredOutputSupport.convert(OUTPUT_READER, text, "triage");
            return output.toResult();
        }
        catch (ModelOutputException exception) {
            throw new ModelOutputException(exception.getMessage(), AlertModelFailureStage.TRIAGE_PARSE);
        }
    }

    private static String extractText(ChatResponse response, String context) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelOutputException(context + " response was empty", AlertModelFailureStage.EMPTY_RESPONSE);
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new ModelOutputException(context + " response text was blank", AlertModelFailureStage.EMPTY_RESPONSE);
        }
        return text;
    }

    private static void reportBoundaryFailure(
            Consumer<AlertModelFailureStage> failureObserver,
            ModelOutputException failure,
            AlertModelFailureStage fallbackStage) {
        failureObserver.accept(failure.failureStage() == null ? fallbackStage : failure.failureStage());
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

    private record TriageModelOutput(
            AlertClassification category,
            AlertPriority priority,
            RiskLevel riskLevel,
            double confidence) {

        private TriageModelOutput {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(riskLevel, "riskLevel");
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }

        private AlertClassificationResult toResult() {
            return new AlertClassificationResult(category, priority, riskLevel, confidence);
        }
    }
}
