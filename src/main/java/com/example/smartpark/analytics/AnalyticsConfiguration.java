package com.example.smartpark.analytics;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.agent.AnalysisSummaryValidator;
import com.example.smartpark.analytics.agent.LlmAnalyticsModelClient;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Bean
    Clock analyticsClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService analyticsExecutor() {
        return Executors.newFixedThreadPool(2);
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
        return new QueryCostGuard(analyticsJdbcTemplate, properties.getMaxPlanCost());
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
                                                 MetricCatalog metricCatalog) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("smartpark.analytics.enabled=true 需要可用的 ChatModel");
        }
        return new LlmAnalyticsModelClient(chatModel, metricCatalog);
    }

    @Bean
    OperationsAnalysisGraph operationsAnalysisGraph(MetricCatalog metricCatalog,
                                                    AnalyticsModelClient analyticsModelClient,
                                                    QueryCostGuard queryCostGuard,
                                                    ReadOnlyQueryExecutor readOnlyQueryExecutor,
                                                    com.example.smartpark.execution.ExecutionEventPublisher publisher,
                                                    Clock analyticsClock) {
        OperationsAnalysisGraph.CostGate costGate =
                (sql, parameters) -> queryCostGuard.estimatedCost(sql.sql(), parameters);
        return new OperationsAnalysisGraph(metricCatalog, analyticsModelClient, costGate,
                readOnlyQueryExecutor::execute, publisher, new AnalysisSummaryValidator(), analyticsClock);
    }

    @Bean
    OperationsAnalysisService operationsAnalysisService(MetricCatalog metricCatalog,
                                                        OperationsAnalysisGraph graph,
                                                        ExecutorService analyticsExecutor,
                                                        AnalyticsProperties properties,
                                                        Clock analyticsClock) {
        return new OperationsAnalysisService(metricCatalog, graph::run, analyticsExecutor,
                properties.getAnalysisTimeout(), analyticsClock);
    }
}
