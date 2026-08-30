package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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
}
