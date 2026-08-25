package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.analytics.model.ChartSpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end analysis workflow over a real PostgreSQL: the language model is a
 * scripted test double (fixed structured responses), while AST guard, EXPLAIN
 * cost gate and read-only executor run against the real database boundary.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsAnalysisGraphTest {

    private static final String GOOD_SQL = """
            SELECT building_id, SUM(kwh) AS total FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs
            GROUP BY building_id ORDER BY building_id LIMIT 100""";
    private static final String LIMIT_LESS_SQL = "SELECT building_id, kwh FROM analytics.v_energy_hourly";

    private PostgreSQLContainer<?> postgres;
    private ScriptedModelClient modelClient;
    private OperationsAnalysisGraph graph;
    private InMemoryExecutionEventPublisher publisher;

    @BeforeAll
    void startContainerAndBuildGraph() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("smartpark");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("analyticsRoPassword", "test-ro-pass"))
                .load()
                .migrate();

        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername("smartpark_analytics_ro");
        dataSource.setPassword("test-ro-pass");

        modelClient = new ScriptedModelClient();
        publisher = new InMemoryExecutionEventPublisher();
        graph = new OperationsAnalysisGraph(
                new MetricCatalog(),
                modelClient,
                (sql, parameters) -> new com.example.smartpark.analytics.sql.QueryCostGuard(
                        new NamedParameterJdbcTemplate(dataSource)).estimatedCost(sql.sql(), parameters),
                (sql, parameters) -> new com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor(dataSource,
                        new com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor.QueryLimits(
                                Duration.ofSeconds(3), 500, 1024L * 1024L, 1_000_000))
                        .execute(sql, parameters),
                publisher,
                new AnalysisSummaryValidator(),
                Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void runsEveryNodeInOrderAndCompletesWithRealResults() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗对比", List.of("能耗"), List.of()),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("total"), "", "kWh"),
                "共 3 行结果。");
        UUID runId = UUID.randomUUID();

        var outcome = graph.run(runId, "上周各楼宇能耗对比");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(outcome.result().rowCount()).isEqualTo(3);
        assertThat(outcome.summary()).isEqualTo("共 3 行结果。");
        assertThat(outcome.chart().type().name()).isEqualTo("BAR");
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(1);

        // All ten nodes started exactly once, in contract order.
        List<String> startedStages = publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.NODE_STARTED)
                .map(ExecutionEvent::safeSummary)
                .toList();
        assertThat(startedStages).containsExactly(
                "理解问题", "解析指标与维度", "召回白名单 Schema", "构建查询计划", "生成 SQL",
                "AST 安全校验", "EXPLAIN 成本检查", "只读执行查询", "生成图表规格", "基于结果总结");
    }

    @Test
    void ambiguousMetricTermPausesRunAsClarificationWithoutTouchingDatabase() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("告警情况", List.of("告警"), List.of()),
                List.of(), null, null);

        var outcome = graph.run(UUID.randomUUID(), "告警情况");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION);
        assertThat(outcome.clarificationQuestions().get(0)).contains("alert_count", "high_risk_alert_count");
        assertThat(modelClient.generateSqlInvocations()).isZero();
    }

    @Test
    void resumedRunPublishesResumedInsteadOfASecondRunStarted() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("total"), "", "kWh"),
                "共 3 行结果。");
        UUID runId = UUID.randomUUID();

        graph.run(runId, "上周能耗", new AnalyticsModelClient.QuestionUnderstanding(
                "上周能耗", List.of("能耗"), List.of()));

        assertThat(publisher.history(runId).get(0).eventType()).isEqualTo(ExecutionEventType.RESUMED);
        assertThat(publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.RUN_STARTED)).isEmpty();
    }

    @Test
    void unknownMetricTermAlsoRequiresClarification() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("客户满意度", List.of("客户满意度"), List.of()),
                List.of(), null, null);

        var outcome = graph.run(UUID.randomUUID(), "客户满意度");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION);
        assertThat(outcome.clarificationQuestions().get(0)).contains("无法识别指标");
    }

    @Test
    void unsafeSqlIsRepairedExactlyOnceThenSucceeds() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(LIMIT_LESS_SQL, GOOD_SQL),
                null,
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(2);
        assertThat(modelClient.lastRejectionReason()).isNotBlank();
    }

    @Test
    void secondUnsafeSqlFailureTerminatesTheRun() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(LIMIT_LESS_SQL, LIMIT_LESS_SQL),
                null, null);

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.FAILED);
        assertThat(outcome.failureStage()).isEqualTo("validateSqlAst");
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(2); // never a third attempt
    }

    @Test
    void invalidChartProposalFallsBackToRealTableColumns() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("LINE", "趋势", "not_a_column", List.of("also_missing"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.chart().type()).isEqualTo(ChartSpec.ChartType.TABLE);
        assertThat(outcome.chart().xField()).isIn(outcome.result().columnNames());
    }

    @Test
    void hallucinatedSummaryNumbersAreRejectedButResultSurvives() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("total"), "", "kWh"),
                "总计高达 99999.99 kWh，环比上升 37%。");

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(outcome.summary()).isEmpty();
        assertThat(outcome.result()).isNotNull();
        assertThat(outcome.chart()).isNotNull();
    }

    /** Scripted model double with fixed structured responses. */
    private static final class ScriptedModelClient implements AnalyticsModelClient {

        private QuestionUnderstanding understanding;
        private Deque<String> sqlDrafts;
        private ChartSpec.Proposal chartProposal;
        private String conclusion;
        private int generateSqlCalls;
        private String lastRejectionReason = "";

        void reset(QuestionUnderstanding understanding, List<String> sqlDrafts,
                   ChartSpec.Proposal chartProposal, String conclusion) {
            this.understanding = understanding;
            this.sqlDrafts = new ArrayDeque<>(sqlDrafts);
            this.chartProposal = chartProposal;
            this.conclusion = conclusion;
            this.generateSqlCalls = 0;
            this.lastRejectionReason = "";
        }

        int generateSqlInvocations() {
            return generateSqlCalls;
        }

        String lastRejectionReason() {
            return lastRejectionReason;
        }

        @Override
        public QuestionUnderstanding understandQuestion(String question) {
            return understanding;
        }

        @Override
        public String generateSql(SqlGenerationRequest request) {
            generateSqlCalls++;
            lastRejectionReason = request.rejectionReason() == null ? "" : request.rejectionReason();
            return sqlDrafts.poll();
        }

        @Override
        public ChartSpec.Proposal proposeChart(ChartContext context) {
            return chartProposal;
        }

        @Override
        public String summarize(SummaryContext context) {
            return conclusion;
        }
    }
}
