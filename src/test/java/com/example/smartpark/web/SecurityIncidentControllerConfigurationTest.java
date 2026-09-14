package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.securityincident.SecurityIncidentConfiguration;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.RuntimeBeanReference;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SecurityIncidentControllerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityIncidentConfiguration.class, ControllerConfiguration.class);

    @Test
    void backsOffWhenIncidentServiceDependenciesAreUnavailable() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(SecurityIncidentController.class));
    }

    @Test
    void backsOffWithoutAServiceInsteadOfRegisteringANullReference() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentWebConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SecurityIncidentController.class));
    }

    @Test
    void registersIncidentServiceWhenDependenciesAreDeclaredByALaterConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentConfiguration.class, SecurityIncidentWebConfiguration.class,
                        ProviderConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(AlertPort.class)
                        .hasSingleBean(SecurityEventReader.class)
                        .hasSingleBean(SecurityIncidentHandoffPort.class)
                        .hasSingleBean(com.example.smartpark.securityincident.SecurityIncidentService.class)
                        .hasSingleBean(SecurityIncidentController.class));
    }

    @Test
    void resolvesAControllerAgainstAnIncidentServiceWithACustomBeanName() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentConfiguration.class, SecurityIncidentWebConfiguration.class,
                        CustomServiceConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("customIncidentService")
                        .hasSingleBean(SecurityIncidentController.class));
    }

    @Test
    void doesNotRegisterASecondIncidentServiceForAnExistingCustomBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentConfiguration.class, SecurityIncidentWebConfiguration.class,
                        ProviderConfiguration.class, CustomServiceOnlyConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("customIncidentService")
                        .doesNotHaveBean("securityIncidentService")
                        .hasSingleBean(SecurityIncidentService.class));
    }

    @Test
    void registersTheIncidentServiceBeforeTheControllerRegardlessOfConfigurationOrder() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentWebConfiguration.class, SecurityIncidentConfiguration.class,
                        ProviderConfiguration.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SecurityIncidentService.class)
                        .hasSingleBean(SecurityIncidentController.class));
    }

    @Test
    void injectsTheSharedAuditTrailIntoTheRegisteredController() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentWebConfiguration.class, SecurityIncidentConfiguration.class,
                        ProviderConfiguration.class)
                .run(context -> {
                    Object argument = context.getBeanFactory().getBeanDefinition("securityIncidentController")
                            .getConstructorArgumentValues().getIndexedArgumentValue(1, Object.class).getValue();
                    assertThat(argument).isInstanceOf(RuntimeBeanReference.class);
                    assertThat(((RuntimeBeanReference) argument).getBeanName()).isEqualTo("auditTrail");
                });
    }

    @Test
    void registersIncidentServiceWhenOnlyAdaptersProvideEvents() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentConfiguration.class, SecurityIncidentWebConfiguration.class,
                        AdapterOnlyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(AlertPort.class)
                            .hasSingleBean(SecuritySourceAdapter.class)
                            .hasSingleBean(SecurityEventReader.class)
                            .hasSingleBean(SecurityIncidentService.class)
                            .hasSingleBean(SecurityIncidentController.class);
                    assertThat(context.getBean(SecurityEventReader.class).listEvents()).isEmpty();
                });
    }

    @Test
    void exposesAdapterEventsThroughTheInjectedSecurityPortInAdapterOnlyDeployments() {
        SecurityEvent adapterEvent = new SecurityEvent("SEC-ADAPTER-ONLY", "PARK-A", "A1", "ACCESS",
                Instant.parse("2026-09-14T00:00:00Z"), "REDACTED: adapter-only event");
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityIncidentConfiguration.class, AdapterOnlyConfiguration.class)
                .run(context -> {
                    when(context.getBean(SecuritySourceAdapter.class).readEvents())
                            .thenReturn(List.of(adapterEvent));
                    assertThat(context.getBean(SecurityPort.class).getEvent("SEC-ADAPTER-ONLY"))
                            .isSameAs(adapterEvent);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        SecurityEventReader securityEventReader() { return org.mockito.Mockito.mock(SecurityEventReader.class); }

        @Bean
        AlertPort alertPort() { return org.mockito.Mockito.mock(AlertPort.class); }

        @Bean
        SecurityIncidentHandoffPort securityIncidentHandoffPort() {
            return org.mockito.Mockito.mock(SecurityIncidentHandoffPort.class);
        }

        @Bean
        AuditTrail auditTrail() { return new AuditTrail(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(SecurityIncidentController.class)
    static class ControllerConfiguration {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdapterOnlyConfiguration {
        @Bean
        SecuritySourceAdapter securitySourceAdapter() {
            return org.mockito.Mockito.mock(SecuritySourceAdapter.class);
        }

        @Bean
        AlertPort alertPort() { return org.mockito.Mockito.mock(AlertPort.class); }

        @Bean
        SecurityIncidentHandoffPort securityIncidentHandoffPort() {
            return org.mockito.Mockito.mock(SecurityIncidentHandoffPort.class);
        }

        @Bean
        AuditTrail auditTrail() { return new AuditTrail(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomServiceConfiguration {
        @Bean("customIncidentService")
        SecurityIncidentService customIncidentService() {
            return org.mockito.Mockito.mock(SecurityIncidentService.class);
        }

        @Bean
        AuditTrail auditTrail() { return new AuditTrail(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomServiceOnlyConfiguration {
        @Bean("customIncidentService")
        SecurityIncidentService customIncidentService() {
            return org.mockito.Mockito.mock(SecurityIncidentService.class);
        }
    }
}
