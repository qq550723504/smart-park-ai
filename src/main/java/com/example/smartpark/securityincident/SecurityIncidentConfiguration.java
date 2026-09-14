package com.example.smartpark.securityincident;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.support.BeanDefinitionLookup;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SecurityIncidentConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor securityIncidentBeanRegistrar() {
        return new SecurityIncidentBeanRegistrar();
    }

    private static final class SecurityIncidentBeanRegistrar
            implements BeanDefinitionRegistryPostProcessor, Ordered {
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (BeanDefinitionLookup.hasBean(registry, SecurityIncidentService.class)
                    || !BeanDefinitionLookup.hasBean(registry, SecurityEventReader.class)
                    || !BeanDefinitionLookup.hasBean(registry, AlertPort.class)
                    || !BeanDefinitionLookup.hasBean(registry, SecurityIncidentHandoffPort.class)) return;
            if (!registry.containsBeanDefinition("securityIncidentStore")) {
                RootBeanDefinition store = new RootBeanDefinition(SecurityIncidentStore.class);
                store.setInstanceSupplier(() -> new SecurityIncidentStore(100));
                registry.registerBeanDefinition("securityIncidentStore", store);
            }
            if (!registry.containsBeanDefinition("securityIncidentService")) {
                RootBeanDefinition service = new RootBeanDefinition(SecurityIncidentService.class);
                service.getConstructorArgumentValues().addIndexedArgumentValue(0,
                        new RuntimeBeanReference(BeanDefinitionLookup.beanNameFor(registry, SecurityEventReader.class)));
                service.getConstructorArgumentValues().addIndexedArgumentValue(1,
                        new RuntimeBeanReference(BeanDefinitionLookup.beanNameFor(registry, AlertPort.class)));
                service.getConstructorArgumentValues().addIndexedArgumentValue(2,
                        new RuntimeBeanReference("securityIncidentStore"));
                service.getConstructorArgumentValues().addIndexedArgumentValue(3,
                        new RuntimeBeanReference(BeanDefinitionLookup.beanNameFor(registry, SecurityIncidentHandoffPort.class)));
                service.getConstructorArgumentValues().addIndexedArgumentValue(4, Clock.systemUTC());
                registry.registerBeanDefinition("securityIncidentService", service);
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
