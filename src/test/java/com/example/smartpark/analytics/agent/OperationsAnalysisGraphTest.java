package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
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
            SELECT building_id, SUM(kwh) AS energy_kwh FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs
            GROUP BY building_id LIMIT 200""";
    private static final String GOOD_TOTAL_SQL = """
            SELECT SUM(kwh) AS energy_kwh FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 200""";
    private static final String LIMIT_LESS_SQL = "SELECT building_id, kwh FROM analytics.v_energy_hourly";

    private PostgreSQLContainer<?> postgres;
    private ScriptedModelClient modelClient;
    private OperationsAnalysisGraph graph;
    private InMemoryExecutionEventPublisher publisher;
    private Map<String, Object> lastExecutionParameters;

    @BeforeAll
    void startContainerAndBuildGraph() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("smartpark");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        com.example.smartpark.analytics.AnalyticsRoleCredentials.sync(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "test-ro-pass");

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
                (sql, parameters) -> {
                    lastExecutionParameters = Map.copyOf(parameters);
                    return new com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor(dataSource,
                            new com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor.QueryLimits(
                                    Duration.ofSeconds(3), 500, 1024L * 1024L, 1_000_000))
                            .execute(sql, parameters);
                },
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
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗对比", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
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
    void preservesEntityFiltersFromUnderstandingThroughPlanValidationAndBinding() {
        String filteredSql = """
                SELECT SUM(kwh) AS energy_kwh FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                  AND building_id = :filter_building_id LIMIT 200""";
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("B1楼宇的能耗", List.of("能耗"),
                        List.of(), null, List.of(), Map.of("building_id", "B1")),
                List.of(filteredSql),
                new ChartSpec.Proposal("TABLE", "B1能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "B1楼宇的能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().filters()).containsExactlyEntriesOf(
                Map.of("building_id", "B1"));
        assertThat(lastExecutionParameters).containsEntry("filter_building_id", "B1");
        assertThat(outcome.result().rowCount()).isEqualTo(1);
    }

    @Test
    void carriesRequestedTimeRangeIntoPlanAndBoundParameters() {
        Instant from = Instant.parse("2026-07-25T00:00:00Z");
        Instant to = Instant.parse("2026-08-24T00:00:00Z");
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding(
                        "过去30天各楼宇能耗", List.of("能耗"), List.of(),
                        new AnalyticsModelClient.RequestedTimeRange(from, to), List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "过去30天各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new com.example.smartpark.analytics.model.QueryPlan.TimeRange(from, to));
        assertThat(lastExecutionParameters)
                .containsEntry("fromTs", Timestamp.from(from))
                .containsEntry("toTs", Timestamp.from(to));
    }

    @Test
    void carriesOnlyUserRequestedDimensionsIntoTheQueryPlan() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding(
                        "过去30天各楼宇能耗", List.of("能耗"), List.of(), null,
                        List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "过去30天各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().dimensions()).containsExactly("building_id");
    }

    @Test
    void mapsDailyGroupingToTheSelectedMetricsTimeColumn() {
        String parkingSql = """
                SELECT stat_date, SUM(entries) AS parking_entries FROM analytics.v_parking_daily
                WHERE stat_date >= :fromTs AND stat_date < :toTs
                GROUP BY stat_date LIMIT 200""";
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("按日查看停车进场量", List.of("停车进场量"), List.of()),
                List.of(parkingSql),
                new ChartSpec.Proposal("LINE", "每日停车进场量", "stat_date", List.of("parking_entries"), "", "辆"),
                "共 2 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "按日查看停车进场量");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().dimensions()).containsExactly("stat_date");
    }

    @Test
    void deduplicatesMetricsAfterCanonicalCatalogResolution() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("能耗和用电量", List.of("能耗", "用电量"), List.of()),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "能耗和用电量");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().metrics()).extracting(metric -> metric.name())
                .containsExactly("energy_kwh");
    }

    @Test
    void usesMetricDefaultLookbackWhenQuestionHasNoRequestedTimeRange() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL), null, "共 3 行结果。");

        graph.run(UUID.randomUUID(), "能耗");

        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new com.example.smartpark.analytics.model.QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")));
    }

    @Test
    void interpretsPreviousWeekUsingParkLocalCalendarBoundaries() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "上周能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new QueryPlan.TimeRange(
                        Instant.parse("2026-08-16T16:00:00Z"),
                        Instant.parse("2026-08-23T16:00:00Z")));
    }

    @Test
    void acceptsModelRangeForExplicitCalendarDates() {
        String question = "2026-08-01 到 2026-08-05 能耗";
        Instant from = Instant.parse("2026-07-31T16:00:00Z");
        Instant to = Instant.parse("2026-08-05T16:00:00Z");
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding(question, List.of("能耗"), List.of(),
                        new AnalyticsModelClient.RequestedTimeRange(from, to)),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "指定日期能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), question);

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange()).isEqualTo(new QueryPlan.TimeRange(from, to));
    }

    @Test
    void usesExplicitCalendarDatesWhenModelOmitsTheRange() {
        String question = "2026-08-01 到 2026-08-05 能耗";
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding(question, List.of("能耗"), List.of()),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "指定日期能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), question);

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange()).isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-07-31T16:00:00Z"), Instant.parse("2026-08-05T16:00:00Z")));
    }

    @Test
    void preservesCategoricalStatusFilterWhenModelOmitsIt() {
        String question = "OPEN 状态的告警数量";
        String filteredAlertSql = """
                SELECT COUNT(*) AS alert_count FROM analytics.v_alert_fact
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                  AND status = :filter_status LIMIT 200""";
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding(question, List.of("告警数量"), List.of()),
                List.of(filteredAlertSql),
                new ChartSpec.Proposal("TABLE", "OPEN 告警数量", "alert_count", List.of(), "", "条"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), question);

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().filters()).containsEntry("status", "OPEN");
        assertThat(lastExecutionParameters).containsEntry("filter_status", "OPEN");
    }

    @Test
    void emitsTheDeclaredSqlLifecycleEventTypesOnTheHappyPath() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");
        UUID runId = UUID.randomUUID();

        graph.run(runId, "上周各楼宇能耗");

        List<ExecutionEventType> types = publisher.history(runId).stream()
                .map(ExecutionEvent::eventType)
                .toList();
        int generated = types.indexOf(ExecutionEventType.SQL_GENERATED);
        int validated = types.indexOf(ExecutionEventType.SQL_VALIDATED);
        int executed = types.indexOf(ExecutionEventType.QUERY_EXECUTED);
        assertThat(generated).as("SQL_GENERATED must be published").isGreaterThanOrEqualTo(0);
        assertThat(validated).as("SQL_VALIDATED must follow generation")
                .isGreaterThan(generated);
        assertThat(executed).as("QUERY_EXECUTED must follow validation")
                .isGreaterThan(validated);
    }

    @Test
    void publishesSqlRejectedWhenGeneratedSqlFailsValidation() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(LIMIT_LESS_SQL, GOOD_SQL),
                null,
                "共 3 行结果。");
        UUID runId = UUID.randomUUID();

        var outcome = graph.run(runId, "上周各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.SQL_REJECTED))
                .as("the rejected draft must surface as SQL_REJECTED before the repair")
                .hasSize(1);
        assertThat(publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.SQL_GENERATED)
                .map(ExecutionEvent::displayPayload))
                .allMatch(payload -> payload instanceof com.example.smartpark.execution.model.DisplayPayload.TextPayload)
                .noneMatch(payload -> payload.toString().contains(LIMIT_LESS_SQL));
        assertThat(publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.SQL_VALIDATED)
                .map(ExecutionEvent::displayPayload))
                .anyMatch(payload -> payload instanceof com.example.smartpark.execution.model.DisplayPayload.SqlPayload sql
                        && GOOD_SQL.equals(sql.safeSql()));
    }

    @Test
    void removesRunContextEvenWhenTheTerminalEventWasAlreadyPublishedByTheTimeoutTimer() {
        // OperationsAnalysisService's independent timer can publish its terminal
        // FAILED event before an interrupted graph enters the catch block; the
        // run context (which retains result rows) must still be removed.
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL), null, null);
        UUID runId = UUID.randomUUID();
        modelClient.onGenerateSql(() -> publisher.publish(new ExecutionEvent(
                UUID.randomUUID(), runId, 0, Instant.now(),
                com.example.smartpark.execution.model.ExecutionScenario.OPERATIONS_ANALYSIS,
                "analytics", com.example.smartpark.execution.model.ExecutionStage.FAILURE,
                ExecutionEventType.FAILED, com.example.smartpark.execution.model.ExecutionStatus.FAILED,
                "timeout", null)));

        var outcome = graph.run(runId, "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.FAILED);
        assertThat(graph.trackedContextCount()).as("run context must not leak after a raced terminal publish")
                .isZero();
    }

    @Test
    void repairsGeneratedSqlWhoseRowLimitExceedsThePlanLimit() {
        // The plan pins the row bound; a model answer with LIMIT 500 widens it
        // and must be rejected with a repairable reason.
        String limit500 = """
                SELECT building_id, SUM(kwh) AS energy_kwh FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id LIMIT 500""";
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(limit500, GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastRejectionReason()).contains("完全一致");
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(2);
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
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");
        UUID runId = UUID.randomUUID();

        graph.run(runId, "上周各楼宇能耗", new AnalyticsModelClient.QuestionUnderstanding(
                "上周各楼宇能耗", List.of("能耗"), List.of()));

        // Lifecycle registration moved to OperationsAnalysisService: the graph
        // itself must not emit RUN_STARTED/RESUMED (the service publishes
        // RESUMED synchronously when resuming, and never a second RUN_STARTED).
        assertThat(publisher.history(runId).stream()
                .filter(event -> event.eventType() == ExecutionEventType.RESUMED
                        || event.eventType() == ExecutionEventType.RUN_STARTED))
                .as("lifecycle events are owned by the service").isEmpty();
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
    void modelMetricMustBeMentionedByTheOriginalQuestion() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周能耗", List.of("parking_entries"), List.of(),
                        null, List.of("building_id")),
                List.of(), null, null);

        var outcome = graph.run(UUID.randomUUID(), "上周能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION);
        assertThat(outcome.clarificationQuestions()).anyMatch(question -> question.contains("原始问题"));
    }

    @Test
    void derivesRecognizedTimeRangeWhenModelOmitsIt() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("过去30天各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "过去30天各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new com.example.smartpark.analytics.model.QueryPlan.TimeRange(
                        Instant.parse("2026-07-25T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")));
    }

    @Test
    void infersExplicitAggregationDimensionWhenModelOmitsIt() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("按楼宇查看能耗", List.of("能耗"), List.of()),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "按楼宇查看能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().dimensions()).containsExactly("building_id");
    }

    @Test
    void interpretsCalendarDayInTheParkTimezone() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("昨天能耗", List.of("能耗"), List.of()),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "昨日能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "昨天能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new com.example.smartpark.analytics.model.QueryPlan.TimeRange(
                        Instant.parse("2026-08-22T16:00:00Z"), Instant.parse("2026-08-23T16:00:00Z")));
    }

    @Test
    void acceptsModelRangeSampledShortlyBeforeServerValidation() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("过去30天能耗", List.of("能耗"), List.of(),
                        new AnalyticsModelClient.RequestedTimeRange(
                                Instant.parse("2026-07-24T23:59:00Z"), Instant.parse("2026-08-23T23:59:00Z"))),
                List.of(GOOD_TOTAL_SQL),
                new ChartSpec.Proposal("TABLE", "能耗", "energy_kwh", List.of(), "", "kWh"),
                "共 1 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "过去30天能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.lastPlan().timeRange())
                .isEqualTo(new com.example.smartpark.analytics.model.QueryPlan.TimeRange(
                        Instant.parse("2026-07-25T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")));
    }

    @Test
    void rejectsModelTimeRangeThatTheOriginalQuestionDidNotRequest() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("昨天能耗", List.of("能耗"), List.of(),
                        new AnalyticsModelClient.RequestedTimeRange(
                                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z")),
                        List.of()),
                List.of(), null, null);

        var outcome = graph.run(UUID.randomUUID(), "昨天能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.FAILED);
    }

    @Test
    void rejectsModelDimensionThatTheOriginalQuestionDidNotRequest() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("总能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(), null, null);

        var outcome = graph.run(UUID.randomUUID(), "总能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.FAILED);
    }

    @Test
    void unsafeSqlIsRepairedExactlyOnceThenSucceeds() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(LIMIT_LESS_SQL, GOOD_SQL),
                null,
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.COMPLETED);
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(2);
        assertThat(modelClient.lastRejectionReason()).isNotBlank();
    }

    @Test
    void secondUnsafeSqlFailureTerminatesTheRun() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(LIMIT_LESS_SQL, LIMIT_LESS_SQL),
                null, null);

        var outcome = graph.run(UUID.randomUUID(), "上周各楼宇能耗");

        assertThat(outcome.outcome()).isEqualTo(OperationsAnalysisGraph.RunOutcome.FAILED);
        assertThat(outcome.failureStage()).isEqualTo("validateSqlAst");
        assertThat(modelClient.generateSqlInvocations()).isEqualTo(2); // never a third attempt
    }

    @Test
    void invalidChartProposalFallsBackToRealTableColumns() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("LINE", "趋势", "not_a_column", List.of("also_missing"), "", "kWh"),
                "共 3 行结果。");

        var outcome = graph.run(UUID.randomUUID(), "上周各楼宇能耗");

        assertThat(outcome.chart().type()).isEqualTo(ChartSpec.ChartType.TABLE);
        assertThat(outcome.chart().xField()).isIn(outcome.result().columnNames());
    }

    @Test
    void hallucinatedSummaryNumbersAreRejectedButResultSurvives() {
        modelClient.reset(
                new AnalyticsModelClient.QuestionUnderstanding("上周各楼宇能耗", List.of("能耗"), List.of(),
                        null, List.of("building_id")),
                List.of(GOOD_SQL),
                new ChartSpec.Proposal("BAR", "分楼宇能耗", "building_id", List.of("energy_kwh"), "", "kWh"),
                "总计高达 99999.99 kWh，环比上升 37%。");

        var outcome = graph.run(UUID.randomUUID(), "上周各楼宇能耗");

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
        private Runnable generateSqlSideEffect;
        private com.example.smartpark.analytics.model.QueryPlan lastPlan;

        void reset(QuestionUnderstanding understanding, List<String> sqlDrafts,
                   ChartSpec.Proposal chartProposal, String conclusion) {
            this.understanding = understanding;
            this.sqlDrafts = new ArrayDeque<>(sqlDrafts);
            this.chartProposal = chartProposal;
            this.conclusion = conclusion;
            this.generateSqlCalls = 0;
            this.lastRejectionReason = "";
            this.lastPlan = null;
            this.generateSqlSideEffect = null;
        }

        /** Side effect run right before generateSql returns (test hook for publisher races). */
        void onGenerateSql(Runnable sideEffect) {
            this.generateSqlSideEffect = sideEffect;
        }

        int generateSqlInvocations() {
            return generateSqlCalls;
        }

        String lastRejectionReason() {
            return lastRejectionReason;
        }

        com.example.smartpark.analytics.model.QueryPlan lastPlan() {
            return lastPlan;
        }

        @Override
        public QuestionUnderstanding understandQuestion(String question) {
            return understanding;
        }

        @Override
        public String generateSql(SqlGenerationRequest request) {
            generateSqlCalls++;
            lastPlan = request.plan();
            lastRejectionReason = request.rejectionReason() == null ? "" : request.rejectionReason();
            String draft = sqlDrafts.poll();
            if (generateSqlSideEffect != null) {
                generateSqlSideEffect.run();
            }
            return draft;
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
