package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
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

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertTriageAgent {

    private static final BeanOutputConverter<TriageModelOutput> OUTPUT_CONVERTER =
            AlertStructuredOutputSupport.converter(TriageModelOutput.class);

    private final ChatModel chatModel;

    public AlertTriageAgent(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    public AlertClassificationResult classify(Alert alert) {
        Objects.requireNonNull(alert, "alert");
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
            return classifyResponse(extractText(chatModel.call(retry), "triage"));
        }
    }

    private AlertClassificationResult classifyResponse(String text) {
        return AlertStructuredOutputSupport.convert(OUTPUT_CONVERTER, text, "triage").toResult();
    }

    private static String extractText(ChatResponse response, String context) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelOutputException(context + " response was empty");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new ModelOutputException(context + " response text was blank");
        }
        return text;
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
