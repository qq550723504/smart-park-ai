package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShowcaseProperties.class)
public class ShowcaseConfiguration {

    @Bean
    ScenarioVerificationRegistry scenarioVerificationRegistry() {
        return new InMemoryScenarioVerificationRegistry();
    }

    @Bean
    ShowcaseScenarioCatalog showcaseScenarioCatalog(
            ScenarioVerificationRegistry registry,
            ShowcaseProperties properties,
            @Value("${smartpark.knowledge.mode:mock}") String knowledgeMode,
            @Value("${smartpark.customer-service.answer-mode:mock}") String customerAnswerMode,
            @Value("${smartpark.analytics.enabled:false}") boolean analyticsEnabled,
            @Value("${smartpark.voice.enabled:false}") boolean voiceEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationProvider) {
        return new ShowcaseScenarioCatalog(registry, properties, knowledgeMode, customerAnswerMode,
                analyticsEnabled, voiceEnabled, collaborationProvider);
    }

    @Bean
    @Qualifier("showcaseClock")
    Clock showcaseClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "showcasePreflightExecutor", destroyMethod = "shutdownNow")
    ExecutorService showcasePreflightExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "showcase-preflight-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(0, ShowcaseScenarioId.values().length,
                60, TimeUnit.SECONDS, new SynchronousQueue<>(), threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ShowcasePreflightService showcasePreflightService(
            ScenarioVerificationRegistry registry,
            @Qualifier("showcaseClock") Clock clock,
            ShowcaseProperties properties,
            @Qualifier("showcasePreflightExecutor") ExecutorService executor,
            List<ShowcasePreflightProbe> probes) {
        return new ShowcasePreflightService(
                registry, clock, properties.getPreflightTimeout(), executor, probes);
    }
}
