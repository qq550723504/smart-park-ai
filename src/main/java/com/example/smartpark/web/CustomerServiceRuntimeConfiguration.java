package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.feedback.FeedbackService;
import com.example.smartpark.operations.OperationsCapabilitiesService;
import com.example.smartpark.operations.OperationsMetrics;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomerServiceRuntimeConfiguration {

    @Bean
    OperationsCapabilitiesService operationsCapabilitiesService(
            @Value("${smartpark.knowledge.mode:mock}") String knowledgeMode,
            @Value("${smartpark.customer-service.answer-mode:mock}") String customerAnswerMode,
            @Value("${smartpark.analytics.enabled:false}") boolean analyticsEnabled,
            @Value("${smartpark.voice.enabled:false}") boolean voiceEnabled,
            @Value("${smartpark.local-demo.enabled:false}") boolean localDemoEnabled,
            org.springframework.beans.factory.ObjectProvider<com.example.smartpark.collaboration.ExpertCollaborationService> collaborationService) {
        return new OperationsCapabilitiesService(knowledgeMode, customerAnswerMode, analyticsEnabled,
                voiceEnabled, localDemoEnabled, collaborationService);
    }

    @Bean
    AuditTrail auditTrail() {
        return new AuditTrail();
    }

    @Bean
    FeedbackService feedbackService() {
        return new FeedbackService();
    }

    @Bean
    OperationsMetrics operationsMetrics(
            org.springframework.beans.factory.ObjectProvider<com.example.smartpark.workflow.WorkflowExecutionStore> workflowStore,
            CustomerServiceWorkflow customerServiceWorkflow,
            AuditTrail auditTrail,
            FeedbackService feedbackService,
            org.springframework.beans.factory.ObjectProvider<KnowledgeAdminPort> knowledgeAdminPort) {
        return new OperationsMetrics(
                workflowStore.getIfAvailable(), customerServiceWorkflow, auditTrail, feedbackService,
                knowledgeAdminPort.getIfAvailable());
    }

    @Bean
    CustomerServiceWorkflow customerServiceWorkflow(KnowledgePort knowledgePort,
                                                    CustomerSessionStore sessionStore,
                                                    CustomerTicketPort ticketPort,
                                                    org.springframework.beans.factory.ObjectProvider<CustomerAnswerPort> answerPort,
                                                    @Value("${smartpark.customer.minimum-knowledge-score:0.70}")
                                                    double minimumKnowledgeScore) {
        return new CustomerServiceWorkflow(knowledgePort, sessionStore, ticketPort,
                answerPort.getIfAvailable(com.example.smartpark.adapter.mock.MockCustomerAnswerAdapter::new),
                java.time.Clock.systemUTC(), () -> "cs-" + java.util.UUID.randomUUID(), minimumKnowledgeScore);
    }

    @Bean
    CustomerSessionStore customerSessionStore() {
        return new InMemoryCustomerSessionStore();
    }

    @Bean
    CustomerTicketPort customerTicketPort() {
        return new InMemoryCustomerTicketAdapter();
    }
}
