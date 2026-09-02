package com.example.smartpark.web;

import com.example.smartpark.securityincident.SecurityIncidentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
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

    @TestConfiguration(proxyBeanMethods = false)
    @Import(SecurityIncidentController.class)
    static class ControllerConfiguration {
    }
}
