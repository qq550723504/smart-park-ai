package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration contract: the analytics capability only exists when explicitly
 * enabled, and enabling it without a complete real-database contract must fail
 * startup instead of silently degrading to any in-memory substitute.
 */
class AnalyticsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalyticsPropertiesTest.TestConfig.class,
                    com.example.smartpark.analytics.AnalyticsConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(AnalyticsProperties.class)
    static class TestConfig {}

    @Test
    void disabledByDefaultAndUsableWhenComplete() {
        runner.withPropertyValues(
                        "smartpark.analytics.enabled=true",
                        "smartpark.analytics.datasource.url=jdbc:postgresql://localhost/smartpark",
                        "smartpark.analytics.datasource.username=smartpark_analytics_ro",
                        "smartpark.analytics.datasource.password=secret",
                        "smartpark.analytics.datasource.admin-username=admin",
                        "smartpark.analytics.datasource.admin-password=secret-admin")
                .run(context -> {
                    assertThat(context).hasSingleBean(AnalyticsProperties.class);
                    var properties = context.getBean(AnalyticsProperties.class);
                    properties.validateUsable();
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMaxRows()).isEqualTo(500);
                    assertThat(properties.getMaxResultBytes()).isEqualTo(1024L * 1024L);
                    assertThat(properties.getStatementTimeout()).isEqualTo("3s");
                });
    }

    @Test
    void incompleteDatasourceContractFailsStartupInsteadOfDegrading() {
        runner.withPropertyValues("smartpark.analytics.enabled=true")
                .run(context -> {
                    var properties = context.getBean(AnalyticsProperties.class);
                    try {
                        properties.validateUsable();
                        throw new AssertionError("expected fail-fast validation error");
                    } catch (IllegalStateException expected) {
                        assertThat(expected.getMessage()).contains("完整的数据源配置");
                    }
                });
    }

    @Test
    void capabilityIsAbsentUnlessExplicitlyEnabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(com.example.smartpark.analytics.catalog.MetricCatalog.class);
            assertThat(context).doesNotHaveBean("analyticsExecutor");
        });
        runner.withPropertyValues("smartpark.analytics.enabled=true").run(context ->
                assertThat(context).hasBean("metricCatalog"));
    }
}
