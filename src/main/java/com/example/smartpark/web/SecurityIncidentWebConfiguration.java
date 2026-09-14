package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.support.BeanDefinitionLookup;
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
            if (registry.containsBeanDefinition("securityIncidentController")
                    || !BeanDefinitionLookup.hasBean(registry, SecurityIncidentService.class)) return;
            String serviceBeanName = BeanDefinitionLookup.beanNameFor(registry, SecurityIncidentService.class);
            if (serviceBeanName == null) return;
            RootBeanDefinition controller = new RootBeanDefinition(SecurityIncidentController.class);
            controller.getConstructorArgumentValues().addIndexedArgumentValue(0,
                    new RuntimeBeanReference(serviceBeanName));
            String auditTrailBeanName = BeanDefinitionLookup.beanNameFor(registry, AuditTrail.class);
            if (auditTrailBeanName != null) {
                controller.getConstructorArgumentValues().addIndexedArgumentValue(1,
                        new RuntimeBeanReference(auditTrailBeanName));
            }
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
}
