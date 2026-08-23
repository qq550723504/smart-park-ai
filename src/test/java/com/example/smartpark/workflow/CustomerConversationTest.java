package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerConversationTest {

    private final AtomicInteger ids = new AtomicInteger();
    private final CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
            new MockParkFixture().knowledge(),
            Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
            () -> "cs-" + ids.incrementAndGet());

    @Test
    void followUpInheritsThePreviousIntentAndKeepsMessageHistory() {
        var first = workflow.handle("访客停车如何登记？", "first-request");
        var second = workflow.reply(first.sessionId(), "具体怎么收费？", "second-request");
        var conversation = workflow.conversation(first.sessionId());

        assertThat(second.intent()).isEqualTo("PARKING");
        assertThat(conversation.messages()).hasSize(4);
        assertThat(conversation.messages()).extracting(CustomerConversation.Message::role)
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
    }

    @Test
    void retrievalTraceContainsQueryAndDocumentIdsButNotTheQuestion() {
        var result = workflow.handle("访客停车如何登记？");
        var trace = workflow.conversation(result.sessionId()).retrievals().get(0);

        assertThat(trace.query()).isEqualTo("parking");
        assertThat(trace.documentIds()).contains("KD-PARKING-001");
        assertThat(trace.toString()).doesNotContain("访客停车如何登记");
    }

    @Test
    void humanTransferStopsAutomaticReplies() {
        var result = workflow.handle("A1 洗手间漏水，需要报修");

        assertThatThrownBy(() -> workflow.reply(result.sessionId(), "现在有人处理吗？", "follow-up"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("human agent");
    }
}
