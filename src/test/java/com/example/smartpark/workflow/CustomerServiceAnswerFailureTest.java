package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceAnswerFailureTest {
    @Test
    void rankedRetrievalFailureCreatesWaitingAgentTicketWithoutLeakingFailureText() {
        KnowledgePort failing = (domain, query) -> { throw new IllegalStateException("legacy search should not be called"); };
        KnowledgePort rankedFailure = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                throw new IllegalStateException("EmbeddingModel/vector store raw failure");
            }
        };
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(rankedFailure,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(),
                (question, intent, evidence) -> { throw new AssertionError("answer generation must not run"); },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-ranked-failure");

        var result = workflow.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(result.answer()).contains("知识检索暂时不可用");
        assertThat(result.answer()).doesNotContain("EmbeddingModel", "vector store raw failure");
    }
    @Test
    void answerPortHumanDecisionSynchronizesReasonAndCitations() {
        KnowledgeDocument document = new KnowledgeDocument("KB-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking", "private body", List.of("parking"), Instant.EPOCH);
        KnowledgePort knowledge = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(document); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) { return List.of(new KnowledgeMatch(document, .9)); }
        };
        CustomerAnswerPort cautious = (question, intent, evidence) ->
                new CustomerAnswer("需要人工核实。", true, CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE, List.of());
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(knowledge,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(), cautious,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-model-handoff");

        var result = workflow.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo(CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE);
        assertThat(result.citationIds()).isEmpty();
        assertThat(result.knowledgeCitations()).isNotEmpty();
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
    }

    @Test
    void answerPortFailureCreatesWaitingAgentTicketWithoutLeakingFailureText() {
        KnowledgeDocument document = new KnowledgeDocument("KB-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking", "private body", List.of("parking"), Instant.EPOCH);
        KnowledgePort knowledge = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(document); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) { return List.of(new KnowledgeMatch(document, .9)); }
        };
        CustomerAnswerPort failing = (question, intent, evidence) -> { throw new IllegalStateException("model raw response secret"); };
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(knowledge,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(), failing,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-failure");

        var result = workflow.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(result.answer()).doesNotContain("model raw response secret");
    }
}
