package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({WorkflowExecutionStore.class, CustomerTicketPort.class})
public class CollaborationCenterConfiguration {

    @Bean
    CollaborationCenterService collaborationCenterService(
            WorkflowExecutionStore workflows, CustomerTicketPort tickets) {
        return new CollaborationCenterService(workflows, tickets);
    }
}
