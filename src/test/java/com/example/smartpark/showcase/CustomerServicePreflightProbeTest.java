package com.example.smartpark.showcase;

import com.example.smartpark.adapter.mock.MockCustomerAnswerAdapter;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServicePreflightProbeTest {

    private final KnowledgePort knowledge = new KnowledgePort() {
        @Override
        public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
            return List.of(new KnowledgeDocument("KD-PARKING-001", KnowledgeDomain.CUSTOMER_SERVICE,
                    "Visitor parking guide", "Visitor parking follows the current notice.",
                    List.of("parking"), Instant.EPOCH));
        }

        @Override
        public List<com.example.smartpark.model.common.KnowledgeMatch> rankedSearch(
                KnowledgeDomain domain, String query) {
            return search(domain, query).stream()
                    .map(document -> new com.example.smartpark.model.common.KnowledgeMatch(document, 0.90))
                    .toList();
        }
    };

    @Test
    void passesWithSupportedAnswerAndCitationWithoutCreatingATicket() {
        CustomerServicePreflightProbe probe = probe(new MockCustomerAnswerAdapter());

        assertThat(probe.probe()).isEqualTo(ShowcaseProbeResult.PASSED);
    }

    @Test
    void failsWhenAnswerIsEmptyOrMissingCitation() {
        CustomerAnswerPort unsupported = (question, intent, evidence) ->
                new CustomerAnswer("已确认", false, CustomerAnswer.Reason.SUPPORTED, List.of());

        assertThat(probe(unsupported).probe()).isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void failsWhenAnswerRequiresHumanHandoff() {
        CustomerAnswerPort handoff = (question, intent, evidence) ->
                new CustomerAnswer("请转人工", true, CustomerAnswer.Reason.INSUFFICIENT_EVIDENCE, List.of());

        assertThat(probe(handoff).probe()).isEqualTo(ShowcaseProbeResult.FAILED);
    }

    private CustomerServicePreflightProbe probe(CustomerAnswerPort answerPort) {
        return new CustomerServicePreflightProbe(
                knowledge, answerPort, 0.70,
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC),
                () -> "preflight-session");
    }
}
