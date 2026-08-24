package com.example.smartpark.adapter.rag;

import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeCustomerAnswerAdapterTest {
    private final KnowledgeMatch parking = new KnowledgeMatch(
            new KnowledgeDocument("KB-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE, "Visitor parking guide", "private knowledge body", List.of("parking"), Instant.EPOCH), .92);

    @Test
    void parsesStructuredModelAnswerAndOnlySendsBoundedEvidenceContext() {
        TestChatModel model = new TestChatModel("""
                {"answer":"请按园区停车指引办理。","needsHuman":false,"reason":"SUPPORTED","citationIds":["KB-PARKING-001"]}
                """);
        DashScopeCustomerAnswerAdapter adapter = new DashScopeCustomerAnswerAdapter(model);

        CustomerAnswer answer = adapter.answer("停车怎么收费？", "PARKING", List.of(parking));

        assertThat(answer.answer()).contains("停车指引");
        assertThat(answer.citationIds()).containsExactly("KB-PARKING-001");
        assertThat(model.lastPrompt().toString()).contains("KB-PARKING-001");
        assertThat(model.lastPrompt().toString()).contains("private knowledge body");
    }

    @Test
    void invalidModelAnswerIsRejectedWithoutReturningModelText() {
        TestChatModel model = new TestChatModel("""
                {"answer":"invented","needsHuman":false,"reason":"SUPPORTED","citationIds":["KB-UNKNOWN"]}
                """);
        DashScopeCustomerAnswerAdapter adapter = new DashScopeCustomerAnswerAdapter(model);

        assertThatThrownBy(() -> adapter.answer("停车怎么收费？", "PARKING", List.of(parking)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customer answer");
    }

    @Test
    void rejectsEmptyEvidenceAndOversizedQuestionBeforeCallingModel() {
        TestChatModel model = new TestChatModel();
        DashScopeCustomerAnswerAdapter adapter = new DashScopeCustomerAnswerAdapter(model);

        assertThatThrownBy(() -> adapter.answer("停车怎么收费？", "PARKING", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.answer("x".repeat(501), "PARKING", List.of(parking)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(model.callCount()).isZero();
    }

    @Test
    void modelFailurePropagatesAsClassifiedFailureForWorkflowToHandoff() {
        TestChatModel model = new TestChatModel();
        DashScopeCustomerAnswerAdapter adapter = new DashScopeCustomerAnswerAdapter(model);

        assertThatThrownBy(() -> adapter.answer("停车怎么收费？", "PARKING", List.of(parking)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TestChatModel");
    }
}
