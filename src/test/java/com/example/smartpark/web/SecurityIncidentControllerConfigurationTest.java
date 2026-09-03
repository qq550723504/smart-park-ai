package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.securityincident.SecurityIncidentConfiguration;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

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
    static class CustomServiceConfiguration {
        @Bean("customIncidentService")
        SecurityIncidentService customIncidentService() {
            return org.mockito.Mockito.mock(SecurityIncidentService.class);
        }

        @Bean
        AuditTrail auditTrail() { return new AuditTrail(); }
    }
}
