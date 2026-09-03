package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CollaborationCenterConfigurationTest {

    @Test
    void projectsHandoffsFromTheSuppliedPortInsteadOfTheBuiltInStore() {
        SecurityIncidentHandoff handoff = new SecurityIncidentHandoff("WI:CUSTOM", "INC-1", "PARK-A", "A1",
                SecurityIncidentRisk.HIGH, "REDACTED: incident", Instant.parse("2026-09-02T08:00:00Z"));

        new ApplicationContextRunner()
                .withUserConfiguration(CollaborationCenterConfiguration.class, CustomPortConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(SecurityIncidentHandoffStore.class)
                            .hasSingleBean(CollaborationCenterService.class);
                    assertThat(context.getBean(CollaborationCenterService.class).list(WorkItemQuery.defaults()))
                            .extracting(CollaborationWorkItem::id).containsExactly(handoff.workItemId());
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomPortConfiguration {
        @Bean
        CustomerServiceWorkflow customerServiceWorkflow() {
            return mock(CustomerServiceWorkflow.class);
        }

        @Bean
        SecurityIncidentHandoffPort securityIncidentHandoffPort() {
            return new SecurityIncidentHandoffPort() {
                @Override
                public SecurityIncidentHandoff createOrGet(com.example.smartpark.securityincident.SecurityIncident incident,
                                                           Instant now) {
                    return handoff();
                }

                @Override
                public List<SecurityIncidentHandoff> list() {
                    return List.of(handoff());
                }

                private SecurityIncidentHandoff handoff() {
                    return new SecurityIncidentHandoff("WI:CUSTOM", "INC-1", "PARK-A", "A1",
                            SecurityIncidentRisk.HIGH, "REDACTED: incident", Instant.parse("2026-09-02T08:00:00Z"));
                }
            };
        }
    }
}
