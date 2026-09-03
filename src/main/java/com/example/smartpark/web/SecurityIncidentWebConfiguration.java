package com.example.smartpark.web;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.securityincident.SecurityIncidentConfiguration;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SecurityIncidentWebConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor securityIncidentControllerRegistrar() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                boolean runtimeDependenciesPresent = hasBean(registry, SecurityEventReader.class)
                        && hasBean(registry, AlertPort.class)
                        && hasBean(registry, SecurityIncidentHandoffPort.class);
                if ((!hasBean(registry, SecurityIncidentService.class)
                        && (!hasBean(registry, SecurityIncidentConfiguration.class)
                        || !runtimeDependenciesPresent))
                        || registry.containsBeanDefinition("securityIncidentController")) return;
                RootBeanDefinition controller = new RootBeanDefinition(SecurityIncidentController.class);
                controller.getConstructorArgumentValues().addIndexedArgumentValue(0,
                        new RuntimeBeanReference("securityIncidentService"));
                registry.registerBeanDefinition("securityIncidentController", controller);
            }

            @Override
            public void postProcessBeanFactory(
                    org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            }
        };
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
