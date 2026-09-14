package com.example.smartpark.securityincident;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventCatalog;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.support.BeanDefinitionLookup;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.ManagedList;
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
            boolean hasEventSource = BeanDefinitionLookup.hasBean(registry, SecurityEventReader.class)
                    || BeanDefinitionLookup.hasBean(registry, SecuritySourceAdapter.class);
            if (BeanDefinitionLookup.hasBean(registry, SecurityIncidentService.class)
                    || !hasEventSource
                    || !BeanDefinitionLookup.hasBean(registry, AlertPort.class)
                    || !BeanDefinitionLookup.hasBean(registry, SecurityIncidentHandoffPort.class)) return;
            if (!registry.containsBeanDefinition("securityIncidentStore")) {
                RootBeanDefinition store = new RootBeanDefinition(SecurityIncidentStore.class);
                store.setInstanceSupplier(() -> new SecurityIncidentStore(100));
                registry.registerBeanDefinition("securityIncidentStore", store);
            }
            if (!registry.containsBeanDefinition("securityIncidentService")) {
                boolean[] synthesizedAggregateReader = new boolean[1];
                String readerBeanName = readerBeanName(registry, synthesizedAggregateReader);
                RootBeanDefinition service = new RootBeanDefinition(SecurityIncidentService.class);
                service.getConstructorArgumentValues().addIndexedArgumentValue(0,
                        new RuntimeBeanReference(readerBeanName));
                service.getConstructorArgumentValues().addIndexedArgumentValue(1,
                        new RuntimeBeanReference(BeanDefinitionLookup.beanNameFor(registry, AlertPort.class)));
                service.getConstructorArgumentValues().addIndexedArgumentValue(2,
                        new RuntimeBeanReference("securityIncidentStore"));
                service.getConstructorArgumentValues().addIndexedArgumentValue(3,
                        new RuntimeBeanReference(BeanDefinitionLookup.beanNameFor(registry, SecurityIncidentHandoffPort.class)));
                service.getConstructorArgumentValues().addIndexedArgumentValue(4, Clock.systemUTC());
                ManagedList<RuntimeBeanReference> adapterReferences = new ManagedList<>();
                // The synthesized reader is already the adapter aggregate, so handing the
                // service the adapters again would ingest every adapter event twice.
                if (!synthesizedAggregateReader[0]) {
                    BeanDefinitionLookup.beanNamesFor(registry, SecuritySourceAdapter.class)
                            .forEach(name -> adapterReferences.add(new RuntimeBeanReference(name)));
                }
                service.getConstructorArgumentValues().addIndexedArgumentValue(5, adapterReferences);
                registry.registerBeanDefinition("securityIncidentService", service);
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }

        /**
         * Uses the registered reader, or installs an aggregate over the adapters when
         * only adapters exist. The aggregate is exposed as the injectable
         * {@code SecurityEventReader}/{@code SecurityPort} so shared tools resolve
         * adapter events instead of hitting an empty reader.
         */
        private static String readerBeanName(BeanDefinitionRegistry registry, boolean[] synthesizedAggregateReader) {
            String existing = BeanDefinitionLookup.beanNameFor(registry, SecurityEventReader.class);
            if (existing != null) return existing;
            if (!registry.containsBeanDefinition("securityEventReader")) {
                ManagedList<RuntimeBeanReference> aggregateAdapters = new ManagedList<>();
                BeanDefinitionLookup.beanNamesFor(registry, SecuritySourceAdapter.class)
                        .forEach(name -> aggregateAdapters.add(new RuntimeBeanReference(name)));
                RootBeanDefinition emptyReader = new RootBeanDefinition(EmptySecurityEventReader.class);
                emptyReader.setInstanceSupplier(EmptySecurityEventReader::new);
                RootBeanDefinition catalog = new RootBeanDefinition(SecurityEventCatalog.class);
                catalog.getConstructorArgumentValues().addIndexedArgumentValue(0, emptyReader);
                catalog.getConstructorArgumentValues().addIndexedArgumentValue(1, aggregateAdapters);
                registry.registerBeanDefinition("securityEventReader", catalog);
                synthesizedAggregateReader[0] = true;
            }
            return "securityEventReader";
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
