package com.example.smartpark.web;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.securityincident.SecurityIncidentConfiguration;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class SecurityIncidentWebConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor securityIncidentControllerRegistrar() {
        return new SecurityIncidentControllerRegistrar();
    }

    private static final class SecurityIncidentControllerRegistrar
            implements BeanDefinitionRegistryPostProcessor, Ordered {
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            boolean runtimeDependenciesPresent = hasBean(registry, SecurityEventReader.class)
                    && hasBean(registry, AlertPort.class)
                    && hasBean(registry, SecurityIncidentHandoffPort.class);
            if ((!hasBean(registry, SecurityIncidentService.class)
                    && (!hasBean(registry, SecurityIncidentConfiguration.class)
                    || !runtimeDependenciesPresent))
                    || registry.containsBeanDefinition("securityIncidentController")) return;
            String serviceBeanName = beanNameFor(registry, SecurityIncidentService.class);
            RootBeanDefinition controller = new RootBeanDefinition(SecurityIncidentController.class);
            controller.getConstructorArgumentValues().addIndexedArgumentValue(0,
                    new RuntimeBeanReference(serviceBeanName));
            registry.registerBeanDefinition("securityIncidentController", controller);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 2;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }

    private static boolean hasBean(BeanDefinitionRegistry registry, Class<?> type) {
        return beanNameFor(registry, type) != null;
    }

    private static String beanNameFor(BeanDefinitionRegistry registry, Class<?> type) {
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (definition.getResolvableType() != org.springframework.core.ResolvableType.NONE
                    && type.isAssignableFrom(definition.getResolvableType().toClass())) return name;
        }
        return null;
    }
}
