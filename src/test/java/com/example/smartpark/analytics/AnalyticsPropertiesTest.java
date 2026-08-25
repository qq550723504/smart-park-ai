package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuration contract: the analytics capability only exists when explicitly
 * enabled, and enabling it without a complete real-database contract must fail
 * startup instead of silently degrading to any in-memory substitute.
 */
class AnalyticsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalyticsPropertiesTest.TestConfig.class,
                    com.example.smartpark.analytics.AnalyticsConfiguration.class);

    private static final String[] FULL_DATASOURCE = {
            "smartpark.analytics.enabled=true",
            "smartpark.analytics.datasource.url=jdbc:postgresql://localhost/smartpark",
            "smartpark.analytics.datasource.username=smartpark_analytics_ro",
            "smartpark.analytics.datasource.password=secret",
            "smartpark.analytics.datasource.admin-username=admin",
            "smartpark.analytics.datasource.admin-password=secret-admin" };

    @Configuration
    @EnableConfigurationProperties(AnalyticsProperties.class)
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        org.springframework.ai.chat.model.ChatModel chatModel() {
            return new com.example.smartpark.agent.TestChatModel();
        }

        @org.springframework.context.annotation.Bean
        com.example.smartpark.execution.ExecutionEventPublisher events() {
            return new com.example.smartpark.execution.InMemoryExecutionEventPublisher();
        }
    }

    @Test
    void disabledByDefaultAndUsableWhenComplete() {
        runner.withPropertyValues(FULL_DATASOURCE)
                .run(context -> {
                    assertThat(context).hasSingleBean(AnalyticsProperties.class);
                    var properties = context.getBean(AnalyticsProperties.class);
                    properties.validateUsable();
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMaxRows()).isEqualTo(500);
                    assertThat(properties.getMaxResultBytes()).isEqualTo(1024L * 1024L);
                    assertThat(properties.getStatementTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
                    // The full runtime is wired: both gates, the graph and the service.
                    assertThat(context).hasBean("analyticsDataSource");
                    assertThat(context).hasBean("queryCostGuard");
                    assertThat(context).hasBean("readOnlyQueryExecutor");
                    assertThat(context).hasBean("operationsAnalysisGraph");
                    assertThat(context).hasBean("operationsAnalysisService");
                });
    }

    @Test
    void incompleteDatasourceContractFailsStartupInsteadOfDegrading() {
        runner.withPropertyValues("smartpark.analytics.enabled=true")
                .run(context -> {
                    Throwable startupFailure = context.getStartupFailure();
                    org.assertj.core.api.Assertions.assertThat(startupFailure).isNotNull();
                    assertThat(startupFailure.getMessage()).contains("完整的数据源配置");
                });
    }

    @Test
    void capabilityIsAbsentUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(com.example.smartpark.analytics.catalog.MetricCatalog.class);
            assertThat(context).doesNotHaveBean("analyticsExecutor");
        });
        runner.withPropertyValues(FULL_DATASOURCE)
                .run(context -> assertThat(context).hasBean("metricCatalog"));
    }

    @Test
    void rejectsNonFiniteOrNonPositivePlanCost() {
        AnalyticsProperties properties = new AnalyticsProperties();

        assertThatThrownBy(() -> properties.setMaxPlanCost(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxPlanCost(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxPlanCost(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
