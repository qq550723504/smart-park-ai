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
    void repairConfirmationSurvivesKnowledgeSearchFailure() {
        KnowledgePort rankedFailure = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                throw new IllegalStateException("EmbeddingModel/vector store raw failure");
            }
        };
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(rankedFailure,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(),
                (question, intent, evidence) -> { throw new AssertionError("repair handoff must not generate an answer"); },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-repair-failure");

        var result = workflow.handle("A1 洗手间漏水，需要报修");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.answer()).contains("已记录设施报修");
        assertThat(result.answer()).doesNotContain("知识检索暂时不可用");
        assertThat(result.reason()).isEqualTo(CustomerAnswer.Reason.POLICY_LIMIT);
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
    }

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
    void answerPortCannotPublishWorkflowOnlyRetrievalFailureReasonAfterSuccessfulRetrieval() {
        KnowledgeDocument document = new KnowledgeDocument("KB-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking", "private body", List.of("parking"), Instant.EPOCH);
        KnowledgePort knowledge = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(document); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) { return List.of(new KnowledgeMatch(document, .9)); }
        };
        CustomerAnswerPort invalid = (question, intent, evidence) ->
                new CustomerAnswer("检索暂不可用。", true, CustomerAnswer.Reason.RETRIEVAL_UNAVAILABLE, List.of());
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(knowledge,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(), invalid,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-invalid-reason");

        var result = workflow.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo(CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE);
        assertThat(result.answer()).contains("当前无法确认答案");
        assertThat(result.answer()).doesNotContain("检索暂不可用");
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

    @Test
    void invalidAnswerCitationsCreateSafeHandoffInsteadOfEscapingTheWorkflow() {
        KnowledgeDocument document = new KnowledgeDocument("KB-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE, "Parking", "private body", List.of("parking"), Instant.EPOCH);
        KnowledgePort knowledge = new KnowledgePort() {
            @Override public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) { return List.of(document); }
            @Override public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) { return List.of(new KnowledgeMatch(document, .9)); }
        };
        CustomerAnswerPort malformed = (question, intent, evidence) ->
                new CustomerAnswer("看起来可以。", false, CustomerAnswer.Reason.SUPPORTED, List.of("UNKNOWN-DOCUMENT"));
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(knowledge,
                new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(), malformed,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "cs-invalid-citation");

        var result = workflow.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo(CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE);
        assertThat(result.citationIds()).isEmpty();
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
    }
}
