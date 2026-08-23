package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.feedback.FeedbackService;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
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
    CustomerServiceWorkflow customerServiceWorkflow(com.example.smartpark.port.knowledge.KnowledgePort knowledgePort) {
        return new CustomerServiceWorkflow(knowledgePort);
    }
}
