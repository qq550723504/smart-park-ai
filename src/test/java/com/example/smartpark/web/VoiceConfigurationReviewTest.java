package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceDeadlines;
import com.example.smartpark.voice.VoiceSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceConfigurationReviewTest {

    @Test
    void bindsSpringStyleDurationsUsedByApplicationYaml() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "smartpark.voice.budgets.max-input-duration=10s",
                        "smartpark.voice.budgets.max-agent-duration=15s",
                        "smartpark.voice.budgets.tts-first-chunk-timeout=5s")
                .run(context -> {
                    VoiceProperties properties = context.getBean(VoiceProperties.class);
                    assertThat(properties.getBudgets().toDeadlines()).isEqualTo(new VoiceDeadlines(
                            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(5)));
                });
    }

    @Test
    void enabledConditionAcceptsSpringBooleanCaseAndApiKeyProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(ActiveMarkerConfig.class)
                .withPropertyValues(
                        "smartpark.voice.enabled=TRUE",
                        "spring.ai.dashscope.api-key=sk-test")
                .run(context -> assertThat(context).hasSingleBean(ActiveMarker.class));
    }

    @Test
    void voiceAgentPoolIsDedicatedAndDeadlineSchedulerIsQualified() throws Exception {
        VoiceSessionConfiguration configuration = new VoiceSessionConfiguration();
        var pool = configuration.voiceAgentPool();
        try {
            assertThat(pool.getPoolSize()).isEqualTo(4);
            Method method = VoiceSessionConfiguration.class.getDeclaredMethod(
                    "deadlineScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
            assertThat(method.getParameterTypes()).hasSize(1);
            assertThat(method.getParameters()[0].getAnnotation(
                    org.springframework.beans.factory.annotation.Qualifier.class).value())
                    .isEqualTo("voiceDeadlineTaskScheduler");
        } finally {
            pool.shutdown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VoiceProperties.class)
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(VoiceActiveCondition.class)
    static class ActiveMarkerConfig {
        @Bean
        ActiveMarker activeMarker() {
            return new ActiveMarker();
        }
    }

    static final class ActiveMarker {
    }
}
