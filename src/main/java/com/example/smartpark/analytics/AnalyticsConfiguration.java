package com.example.smartpark.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.smartpark.analytics.catalog.MetricCatalog;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Registers the governed analytics building blocks only when the capability is
 * explicitly enabled. Enabling without complete real-database configuration
 * fails startup by contract (see AnalyticsProperties.validateUsable).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartpark.analytics.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

    @Bean
    MetricCatalog metricCatalog() {
        return new MetricCatalog();
    }

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService analyticsExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
