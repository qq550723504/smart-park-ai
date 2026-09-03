package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CollaborationCenterConfiguration {

    @Bean
    CollaborationSlaSnapshotStore collaborationSlaSnapshotStore() {
        return new CollaborationSlaSnapshotStore();
    }

    @Bean
    CollaborationCenterService collaborationCenterService(
            ObjectProvider<WorkflowExecutionStore> workflows, CustomerServiceWorkflow customerService,
            CollaborationSlaSnapshotStore snapshots, SecurityIncidentHandoffPort incidentHandoffs) {
        return new CollaborationCenterService(workflows.getIfAvailable(), customerService, Clock.systemUTC(), snapshots,
                incidentHandoffs);
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor securityIncidentHandoffStoreRegistrar() {
        return new SecurityIncidentHandoffStoreRegistrar();
    }

    private static final class SecurityIncidentHandoffStoreRegistrar
            implements BeanDefinitionRegistryPostProcessor, Ordered {
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (hasBean(registry, SecurityIncidentHandoffPort.class)
                    || registry.containsBeanDefinition("securityIncidentHandoffStore")) return;
            RootBeanDefinition store = new RootBeanDefinition(SecurityIncidentHandoffStore.class);
            store.setInstanceSupplier(() -> new SecurityIncidentHandoffStore(100));
            registry.registerBeanDefinition("securityIncidentHandoffStore", store);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }

    private static boolean hasBean(BeanDefinitionRegistry registry, Class<?> type) {
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (definition.getResolvableType() != org.springframework.core.ResolvableType.NONE
                    && type.isAssignableFrom(definition.getResolvableType().toClass())) return true;
        }
        return false;
    }
}
