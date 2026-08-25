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
                    assertThat(properties.getClarificationTimeout()).isEqualTo(java.time.Duration.ofMinutes(5));
                    // The full runtime is wired: both gates, the graph and the service.
                    assertThat(context).hasBean("analyticsDataSource");
                    assertThat(context).hasBean("queryCostGuard");
                    assertThat(context).hasBean("readOnlyQueryExecutor");
                    assertThat(context).hasBean("operationsAnalysisGraph");
                    assertThat(context).hasBean("operationsAnalysisService");
                    assertThat(context).doesNotHaveBean(DemoSnapshotRefresher.class);
                });
    }

    @Test
    void demoSnapshotRefreshRequiresExplicitOptIn() {
        runner.withPropertyValues(FULL_DATASOURCE)
                .withPropertyValues("smartpark.analytics.demo-snapshot-refresh-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DemoSnapshotRefresher.class));
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
    void rejectsPrivilegedRuntimeLoginInsteadOfWeakeningTheMigratedRoleBoundary() {
        AnalyticsProperties properties = new AnalyticsProperties();
        properties.setEnabled(true);
        properties.getDatasource().setUrl("jdbc:postgresql://localhost/smartpark");
        properties.getDatasource().setUsername("admin");
        properties.getDatasource().setPassword("secret");
        properties.getDatasource().setAdminUsername("admin");
        properties.getDatasource().setAdminPassword("secret-admin");

        assertThatThrownBy(properties::validateUsable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smartpark_analytics_ro");
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

    @Test
    void rejectsNonPositiveOrOverLargeRowAndByteCaps() {
        AnalyticsProperties properties = new AnalyticsProperties();

        assertThatThrownBy(() -> properties.setMaxRows(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxRows(-5)).isInstanceOf(IllegalArgumentException.class);
        // The executor cannot accept SQL beyond the AST guard's LIMIT contract.
        assertThatThrownBy(() -> properties.setMaxRows(501)).isInstanceOf(IllegalArgumentException.class);
        assertThat(properties.getMaxRows()).isEqualTo(500); // default unchanged

        assertThatThrownBy(() -> properties.setMaxResultBytes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMaxResultBytes(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveClarificationTimeout() {
        AnalyticsProperties properties = new AnalyticsProperties();

        assertThatThrownBy(() -> properties.setClarificationTimeout(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clarification-timeout");
        assertThatThrownBy(() -> properties.setClarificationTimeout(java.time.Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clarification-timeout");
    }
}
