package com.example.smartpark.collaborationcenter;

import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CollaborationCenterConfiguration {

    @Bean
    CollaborationCenterService collaborationCenterService(
            ObjectProvider<WorkflowExecutionStore> workflows, CustomerServiceWorkflow customerService) {
        return new CollaborationCenterService(workflows.getIfAvailable(), customerService, Clock.systemUTC());
    }
}
