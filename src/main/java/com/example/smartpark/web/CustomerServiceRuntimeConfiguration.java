package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.feedback.FeedbackService;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomerServiceRuntimeConfiguration {

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
                                                    CustomerTicketPort ticketPort) {
        return new CustomerServiceWorkflow(knowledgePort, sessionStore, ticketPort,
                java.time.Clock.systemUTC(), () -> "cs-" + java.util.UUID.randomUUID());
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
