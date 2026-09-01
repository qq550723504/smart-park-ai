package com.example.smartpark.showcase;

import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.adapter.mock.MockCustomerAnswerAdapter;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class CustomerServicePreflightProbe implements ShowcasePreflightProbe {
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
                knowledgePort,
                new InMemoryCustomerSessionStore(clock, 2, java.time.Duration.ofMinutes(5)),
                new InMemoryCustomerTicketAdapter(),
                answerPort,
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
            return ShowcaseProbeResult.FAILED;
        }
    }
}
