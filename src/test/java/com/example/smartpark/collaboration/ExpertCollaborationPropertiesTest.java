package com.example.smartpark.collaboration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertCollaborationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void usesSafeDefaults() {
        runner.run(context -> {
            var properties = context.getBean(ExpertCollaborationProperties.class);
            assertThat(properties.getExpertTimeout()).isEqualTo(java.time.Duration.ofSeconds(15));
            assertThat(properties.getRunTimeout()).isEqualTo(java.time.Duration.ofSeconds(40));
            assertThat(properties.getMaxParallel()).isEqualTo(3);
        });
    }

    @Test
    void bindsExplicitDurationsAndParallelLimit() {
        runner.withPropertyValues(
                "smartpark.collaboration.expert-timeout=9s",
                "smartpark.collaboration.run-timeout=31s",
                "smartpark.collaboration.max-parallel=2")
                .run(context -> {
                    var properties = context.getBean(ExpertCollaborationProperties.class);
                    assertThat(properties.getExpertTimeout()).isEqualTo(java.time.Duration.ofSeconds(9));
                    assertThat(properties.getRunTimeout()).isEqualTo(java.time.Duration.ofSeconds(31));
                    assertThat(properties.getMaxParallel()).isEqualTo(2);
                });
    }

    @Test
    void rejectsParallelismOutsideBoundedFanOut() {
        runner.withPropertyValues("smartpark.collaboration.max-parallel=4")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void exposesConfiguredExpertTimeoutToRuntime() {
        var properties = new ExpertCollaborationProperties();
        properties.setExpertTimeout(java.time.Duration.ofMillis(25));
        assertThat(properties.getExpertTimeout()).isEqualTo(java.time.Duration.ofMillis(25));
    }
    @Test
    void rejectsNonPositiveTimeout() {
        runner.withPropertyValues("smartpark.collaboration.expert-timeout=0s")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @EnableConfigurationProperties(ExpertCollaborationProperties.class)
    static class TestConfiguration { }
}
