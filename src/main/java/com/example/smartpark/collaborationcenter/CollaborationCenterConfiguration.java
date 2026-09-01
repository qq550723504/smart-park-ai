package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
public class CollaborationCenterConfiguration {

    @Bean
    CollaborationCenterService collaborationCenterService(
            ObjectProvider<WorkflowExecutionStore> workflows, CustomerTicketPort tickets) {
        return new CollaborationCenterService(workflows.getIfAvailable(), tickets);
    }
}
