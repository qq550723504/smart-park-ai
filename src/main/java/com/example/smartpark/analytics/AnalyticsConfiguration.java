package com.example.smartpark.analytics;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.flywaydb.core.Flyway;

import java.time.Duration;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.agent.AnalysisSummaryValidator;
import com.example.smartpark.analytics.agent.LlmAnalyticsModelClient;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import com.example.smartpark.analytics.agent.TimeIntentProvider;
import com.example.smartpark.analytics.agent.time.JioNlpClient;
import com.example.smartpark.analytics.agent.time.JioNlpTimeIntentProvider;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Registers the complete governed analytics runtime only when the capability
 * is explicitly enabled: its own read-only DataSource, both SQL gates, the
 * graph and the run service. Enabling without a complete real-database or
 * model contract fails startup by contract (see AnalyticsProperties.validateUsable).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartpark.analytics.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

    @Bean
    MetricCatalog metricCatalog() {
        return new MetricCatalog();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "smartpark.analytics.demo-data-refresh-enabled", havingValue = "true")
    DemoDataRefresher demoDataRefresher(AnalyticsProperties properties,
                                        ObjectProvider<Flyway> analyticsFlywayProvider) {
        // Force the explicit migration bean first when production enables it;
        // isolated wiring tests may intentionally omit Flyway and use a fake DB.
        analyticsFlywayProvider.getIfAvailable();
        properties.validateUsable();
        var refresher = new DemoDataRefresher(properties.getDatasource().getUrl(),
                properties.getDatasource().getAdminUsername(),
                properties.getDatasource().getAdminPassword(),
                Duration.ofHours(1));
        refresher.start();
        return refresher;
    }

    @Bean
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
    Flyway analyticsFlyway(AnalyticsProperties properties) {
        properties.validateUsable();
        Flyway flyway = Flyway.configure()
                .dataSource(properties.getDatasource().getUrl(),
                        properties.getDatasource().getAdminUsername(),
                        properties.getDatasource().getAdminPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService analyticsExecutor() {
        // Timed-out provider calls may ignore interruption. A bounded queue
        // makes overload visible instead of retaining cancelled analyses until
        // an uncooperative worker eventually returns.
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnMissingBean
    AnalyticsRoleCredentialProvisioner analyticsRoleCredentialProvisioner() {
        return properties -> AnalyticsRoleCredentials.sync(properties.getDatasource().getUrl(),
                properties.getDatasource().getAdminUsername(), properties.getDatasource().getAdminPassword(),
                properties.getDatasource().getPassword());
    }

    @Bean
    org.springframework.boot.ApplicationRunner analyticsRolePasswordProvisioner(
            AnalyticsProperties properties, AnalyticsRoleCredentialProvisioner provisioner,
            ObjectProvider<Flyway> analyticsFlywayProvider) {
        // Binds the configured read-only credential to the role created
        // password-less by V1 — via the single audited quoting point, never
        // through migration SQL interpolation. Runs at application startup and
        // fails the application context if the runtime credential cannot be
        // provisioned; an analytics instance must never advertise a broken
        // database boundary.
        return args -> {
            // Resolve Flyway before touching the role created by V1. This keeps
            // lazy-initialized deployments from provisioning against an
            // unmigrated database; isolated wiring tests may omit Flyway.
            analyticsFlywayProvider.getIfAvailable();
            properties.validateUsable();
            provisioner.provision(properties);
        };
    }

    @Bean
    DataSource analyticsDataSource(AnalyticsProperties properties) throws Exception {
        properties.validateUsable();
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        // The driver is a runtime-only dependency; load it reflectively.
        dataSource.setDriver((java.sql.Driver) Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance());
        dataSource.setUrl(properties.getDatasource().getUrl());
        // The application always talks to the database through the read-only role.
        dataSource.setUsername(properties.getDatasource().getUsername());
        dataSource.setPassword(properties.getDatasource().getPassword());
        return dataSource;
    }

    @Bean
    NamedParameterJdbcTemplate analyticsJdbcTemplate(DataSource analyticsDataSource) {
        return new NamedParameterJdbcTemplate(analyticsDataSource);
    }

    @Bean
    QueryCostGuard queryCostGuard(NamedParameterJdbcTemplate analyticsJdbcTemplate,
                                  AnalyticsProperties properties) {
        return new QueryCostGuard(analyticsJdbcTemplate, properties.getMaxPlanCost(),
                properties.getStatementTimeout());
    }

    @Bean
    ReadOnlyQueryExecutor readOnlyQueryExecutor(DataSource analyticsDataSource,
                                                AnalyticsProperties properties) {
        return new ReadOnlyQueryExecutor(analyticsDataSource, new ReadOnlyQueryExecutor.QueryLimits(
                properties.getStatementTimeout(),
                properties.getMaxRows(),
                properties.getMaxResultBytes(),
                properties.getMaxPlanCost()));
    }

    @Bean
    AnalyticsModelClient llmAnalyticsModelClient(ObjectProvider<ChatModel> chatModelProvider,
                                                 MetricCatalog metricCatalog,
                                                 Clock analyticsClock) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("smartpark.analytics.enabled=true 需要可用的 ChatModel");
        }
        return new LlmAnalyticsModelClient(chatModel, metricCatalog, analyticsClock);
    }

    @Bean
    JioNlpClient jioNlpClient(AnalyticsProperties properties) {
        AnalyticsProperties.TimeIntent config = properties.getTimeIntent();
        if (!config.isEnabled()) {
            throw new IllegalStateException("smartpark.analytics.time-intent.enabled must remain true");
        }
        return new JioNlpClient(config.getUrl(), config.getConnectTimeout(), config.getReadTimeout(),
                config.getMaxResponseBytes(), config.getExpectedProvider(), config.getExpectedVersion());
    }

    @Bean
    TimeIntentProvider timeIntentProvider(JioNlpClient client, AnalyticsProperties properties) {
        return new JioNlpTimeIntentProvider(client, properties.getTimeIntent().getTimezone());
    }

    @Bean
    OperationsAnalysisGraph operationsAnalysisGraph(MetricCatalog metricCatalog,
                                                    AnalyticsModelClient analyticsModelClient,
                                                    QueryCostGuard queryCostGuard,
                                                    ReadOnlyQueryExecutor readOnlyQueryExecutor,
                                                    com.example.smartpark.execution.ExecutionEventPublisher publisher,
                                                    Clock analyticsClock,
                                                    AnalyticsProperties properties,
                                                    TimeIntentProvider timeIntentProvider) {
        OperationsAnalysisGraph.CostGate costGate =
                (sql, parameters) -> queryCostGuard.estimatedCost(sql.sql(), parameters);
        return new OperationsAnalysisGraph(metricCatalog, analyticsModelClient, costGate,
                readOnlyQueryExecutor::execute, publisher, new AnalysisSummaryValidator(), analyticsClock,
                properties.getAnalysisTimeout(), timeIntentProvider);
    }

    @Bean
    OperationsAnalysisService operationsAnalysisService(MetricCatalog metricCatalog,
                                                        OperationsAnalysisGraph graph,
                                                        ExecutorService analyticsExecutor,
                                                        AnalyticsProperties properties,
                                                        Clock analyticsClock,
                                                        com.example.smartpark.execution.ExecutionEventPublisher publisher) {
        return new OperationsAnalysisService(metricCatalog, graph::run, analyticsExecutor,
                properties.getAnalysisTimeout(), properties.getClarificationTimeout(), analyticsClock, publisher);
    }
}
