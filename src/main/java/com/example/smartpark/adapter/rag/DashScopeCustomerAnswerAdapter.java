package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** DashScope-compatible structured answer adapter. No model text is logged or returned on failure. */
@Component
@ConditionalOnProperty(name = "smartpark.customer-service.answer-mode", havingValue = "dashscope")
public final class DashScopeCustomerAnswerAdapter implements CustomerAnswerPort {
    static final int MAX_EVIDENCE_DOCUMENTS = 5;
    static final int MAX_EVIDENCE_CHARACTERS = 2_000;
    static final int MAX_QUESTION_CHARACTERS = 500;
    private final ChatClient chatClient;

    public DashScopeCustomerAnswerAdapter(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(Objects.requireNonNull(chatModel, "chatModel")).build();
    }

    @Override
    public CustomerAnswer answer(String question, String intent, List<KnowledgeMatch> evidence) {
        String safeQuestion = Objects.requireNonNull(question, "question").trim();
        if (safeQuestion.isBlank() || safeQuestion.length() > MAX_QUESTION_CHARACTERS) {
            throw new IllegalArgumentException("question must be between 1 and " + MAX_QUESTION_CHARACTERS + " characters");
        }
        List<KnowledgeMatch> safeEvidence = List.copyOf(Objects.requireNonNull(evidence, "evidence")).stream()
                .limit(MAX_EVIDENCE_DOCUMENTS).toList();
        if (safeEvidence.isEmpty()) throw new IllegalArgumentException("evidence must not be empty");
        String prompt = CustomerAnswerContract.userMessage(intent, safeQuestion, safeEvidence, MAX_EVIDENCE_CHARACTERS);
        String response = chatClient.prompt()
                .system(CustomerAnswerContract.systemMessage())
                .user(prompt)
                .call()
                .content();
        return StructuredCustomerAnswerParser.parse(response, safeEvidence);
    }
}
