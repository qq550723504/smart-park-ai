package com.example.smartpark.showcase;

import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.adapter.mock.MockCustomerAnswerAdapter;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class CustomerServicePreflightProbe implements ShowcasePreflightProbe {
    private static final Logger log = LoggerFactory.getLogger(CustomerServicePreflightProbe.class);
    private final KnowledgePort knowledgePort;
    private final CustomerAnswerPort answerPort;
    private final double minimumKnowledgeScore;
    private final Clock clock;
    private final Supplier<String> sessionIds;

    @Autowired
    public CustomerServicePreflightProbe(
            KnowledgePort knowledgePort,
            ObjectProvider<CustomerAnswerPort> answerProvider,
            @Value("${smartpark.customer.minimum-knowledge-score:0.70}") double minimumKnowledgeScore) {
        this(knowledgePort, answerProvider.getIfAvailable(MockCustomerAnswerAdapter::new),
                minimumKnowledgeScore, Clock.systemUTC(), () -> "preflight-customer-" + java.util.UUID.randomUUID());
    }

    CustomerServicePreflightProbe(
            KnowledgePort knowledgePort,
            CustomerAnswerPort answerPort,
            double minimumKnowledgeScore,
            Clock clock,
            Supplier<String> sessionIds) {
        this.knowledgePort = Objects.requireNonNull(knowledgePort, "knowledgePort");
        this.answerPort = Objects.requireNonNull(answerPort, "answerPort");
        this.minimumKnowledgeScore = minimumKnowledgeScore;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
    }

    @Override
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.CUSTOMER_SERVICE;
    }

    @Override
    public ShowcaseProbeResult probe() {
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                diagnosticKnowledgePort(),
                new InMemoryCustomerSessionStore(clock, 2, java.time.Duration.ofMinutes(5)),
                new InMemoryCustomerTicketAdapter(),
                diagnosticAnswerPort(),
                clock,
                sessionIds,
                minimumKnowledgeScore);
        try {
            CustomerServiceResult result = workflow.handle(
                    ShowcaseLaunchInput.forScenario(scenarioId()).question());
            boolean supported = result != null
                    && !result.answer().isBlank()
                    && !result.needsHuman()
                    && result.ticket() == null
                    && !result.knowledgeCitations().isEmpty()
                    && !result.citationIds().isEmpty()
                    && workflow.sessionCount() == 1
                    && workflow.tickets().isEmpty();
            return supported ? ShowcaseProbeResult.PASSED : ShowcaseProbeResult.FAILED;
        } catch (RuntimeException failure) {
            log.warn("customer service preflight failed: stage=WORKFLOW, exceptionType={}",
                    failure.getClass().getName());
            return ShowcaseProbeResult.FAILED;
        }
    }

    private KnowledgePort diagnosticKnowledgePort() {
        return new KnowledgePort() {
            @Override
            public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                try {
                    return knowledgePort.search(domain, query);
                } catch (RuntimeException failure) {
                    log.warn("customer service preflight failed: stage=KNOWLEDGE, exceptionType={}",
                            failure.getClass().getName());
                    throw failure;
                }
            }

            @Override
            public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                try {
                    return knowledgePort.rankedSearch(domain, query);
                } catch (RuntimeException failure) {
                    log.warn("customer service preflight failed: stage=KNOWLEDGE, exceptionType={}",
                            failure.getClass().getName());
                    throw failure;
                }
            }
        };
    }

    private CustomerAnswerPort diagnosticAnswerPort() {
        return (question, intent, evidence) -> {
            try {
                return answerPort.answer(question, intent, evidence);
            } catch (RuntimeException failure) {
                log.warn("customer service preflight failed: stage=ANSWER, exceptionType={}",
                        failure.getClass().getName());
                throw failure;
            }
        };
    }
}
