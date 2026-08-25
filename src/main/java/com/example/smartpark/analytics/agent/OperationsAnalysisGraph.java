package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricResolution;
import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.UnsafeSqlException;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nine-node analysis workflow on the native 2.0 StateGraph:
 * understandQuestion → resolveMetricAndDimensions → recallAllowedSchema →
 * buildQueryPlan → generateSql → validateSqlAst → explainAndCheckCost →
 * executeReadOnlyQuery → buildChartSpec → summarizeFromResult.
 *
 * The graph owns routing and ordering; typed payloads travel through a
 * per-run context so no serialization round-trips can drop business objects.
 * SQL is only carried forward after AST validation, at most one repair is
 * allowed, and the conclusion is validated against executed results.
 */
public class OperationsAnalysisGraph {

    private static final String STATE_QUESTION = "question";
    private static final String STATE_RUN_ID = "runId";
    private static final int MAX_SQL_ATTEMPTS = 2; // one original try, at most one repair

    private final MetricCatalog catalog;
    private final AnalyticsModelClient modelClient;
    private final CostGate costGate;
    private final ExecutionGate executionGate;
    private final ExecutionEventPublisher publisher;
    private final AnalysisSummaryValidator summaryValidator;
    private final Clock clock;
    private final Duration executionTimeout;
    private final CompiledGraph compiled;
    private final ConcurrentHashMap<UUID, RunContext> contexts = new ConcurrentHashMap<>();

    /** Cost boundary abstraction backed by QueryCostGuard in production wiring. */
    public interface CostGate {
        void check(ValidatedSql sql, java.util.Map<String, Object> parameters) throws UnsafeSqlException;
    }

    /** Execution boundary abstraction backed by ReadOnlyQueryExecutor in production wiring. */
    public interface ExecutionGate {
        TabularResult execute(ValidatedSql sql, java.util.Map<String, Object> parameters) throws UnsafeSqlException;
    }

    public enum RunOutcome { COMPLETED, NEEDS_CLARIFICATION, FAILED }

    public record AnalysisRunResult(
            UUID runId,
            RunOutcome outcome,
            List<String> clarificationQuestions,
            /** Structured candidate metric names, one candidate set per pending question. */
            List<List<String>> clarificationOptions,
            ChartSpec chart,
            TabularResult result,
            String summary,
            AnalyticsModelClient.QuestionUnderstanding understanding,
            String failureStage) {

        public AnalysisRunResult(UUID runId, RunOutcome outcome,
                                 List<String> clarificationQuestions,
                                 List<List<String>> clarificationOptions,
                                 ChartSpec chart, TabularResult result,
                                 String summary, String failureStage) {
            this(runId, outcome, clarificationQuestions, clarificationOptions,
                    chart, result, summary, null, failureStage);
        }

        static AnalysisRunResult failed(UUID runId, String stage) {
            return new AnalysisRunResult(runId, RunOutcome.FAILED, List.of(), List.of(), null, null, null, null, stage);
        }

        static AnalysisRunResult needsClarification(UUID runId, List<String> questions,
                                                    List<List<String>> options,
                                                    AnalyticsModelClient.QuestionUnderstanding understanding) {
            return new AnalysisRunResult(runId, RunOutcome.NEEDS_CLARIFICATION, questions, options,
                    null, null, null, understanding, null);
        }
    }

    private static final class RunContext {
        AnalyticsModelClient.QuestionUnderstanding understanding;
        AnalyticsModelClient.QuestionUnderstanding pinnedUnderstanding;
        List<com.example.smartpark.analytics.catalog.MetricDefinition> metrics = List.of();
        String schemaDescription = "";
        QueryPlan plan;
        int sqlAttempts;
        String rejectionReason;
        String sqlDraft;
        ValidatedSql validatedSql;
        /** Named-parameter values bound identically into the cost check and execution. */
        Map<String, Object> parameters = Map.of();
        TabularResult result;
        ChartSpec chart;
        String summary = "";
        volatile String status = "RUNNING";
        String failureStage;
        final List<String> clarificationQuestions = new ArrayList<>();
        final List<List<String>> clarificationOptions = new ArrayList<>();
    }

    public OperationsAnalysisGraph(MetricCatalog catalog,
                                   AnalyticsModelClient modelClient,
                                   CostGate costGate,
                                   ExecutionGate executionGate,
                                   ExecutionEventPublisher publisher,
                                   AnalysisSummaryValidator summaryValidator,
                                   Clock clock) {
        this(catalog, modelClient, costGate, executionGate, publisher, summaryValidator, clock,
                Duration.ofSeconds(60));
    }

    public OperationsAnalysisGraph(MetricCatalog catalog,
                                   AnalyticsModelClient modelClient,
                                   CostGate costGate,
                                   ExecutionGate executionGate,
                                   ExecutionEventPublisher publisher,
                                   AnalysisSummaryValidator summaryValidator,
                                   Clock clock,
                                   Duration executionTimeout) {
        this.catalog = catalog;
        this.modelClient = modelClient;
        this.costGate = costGate;
        this.executionGate = executionGate;
        this.publisher = publisher == null ? new InMemoryExecutionEventPublisher() : publisher;
        this.summaryValidator = summaryValidator;
        this.clock = clock;
        if (executionTimeout == null || executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("executionTimeout must be positive");
        }
        this.executionTimeout = executionTimeout;
        try {
            this.compiled = build();
        } catch (Exception exception) {
            throw new IllegalStateException("unable to compile operations analysis graph", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private CompiledGraph build() throws Exception {
        StateGraph graph = new StateGraph(() -> {
            Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of(STATE_QUESTION, STATE_RUN_ID)) {
                strategies.put(key, new ReplaceStrategy());
            }
            return strategies;
        });

        graph.addNode("understandQuestion", AsyncNodeAction.node_async(this::understandQuestion));
        graph.addNode("resolveMetricAndDimensions", AsyncNodeAction.node_async(this::resolveMetricAndDimensions));
        graph.addNode("recallAllowedSchema", AsyncNodeAction.node_async(this::recallAllowedSchema));
        graph.addNode("buildQueryPlan", AsyncNodeAction.node_async(this::buildQueryPlan));
        graph.addNode("generateSql", AsyncNodeAction.node_async(this::generateSql));
        graph.addNode("validateSqlAst", AsyncNodeAction.node_async(this::validateSqlAst));
        graph.addNode("explainAndCheckCost", AsyncNodeAction.node_async(this::explainAndCheckCost));
        graph.addNode("executeReadOnlyQuery", AsyncNodeAction.node_async(this::executeReadOnlyQueryNode));
        graph.addNode("buildChartSpec", AsyncNodeAction.node_async(this::buildChartSpecNode));
        graph.addNode("summarizeFromResult", AsyncNodeAction.node_async(this::summarizeFromResult));

        graph.addEdge(StateGraph.START, "understandQuestion");
        graph.addConditionalEdges("understandQuestion", AsyncEdgeAction.edge_async(state ->
                        needsClarification(state) ? "END" : "resolveMetricAndDimensions"),
                Map.of("END", StateGraph.END, "resolveMetricAndDimensions", "resolveMetricAndDimensions"));
        graph.addConditionalEdges("resolveMetricAndDimensions", AsyncEdgeAction.edge_async(state ->
                        needsClarification(state) ? "END" : "recallAllowedSchema"),
                Map.of("END", StateGraph.END, "recallAllowedSchema", "recallAllowedSchema"));
        graph.addEdge("recallAllowedSchema", "buildQueryPlan");
        graph.addEdge("buildQueryPlan", "generateSql");
        graph.addEdge("generateSql", "validateSqlAst");
        graph.addConditionalEdges("validateSqlAst", AsyncEdgeAction.edge_async(this::routeAfterSqlCheck), Map.of(
                "explainAndCheckCost", "explainAndCheckCost",
                "generateSql", "generateSql",
                "END", StateGraph.END));
        graph.addConditionalEdges("explainAndCheckCost", AsyncEdgeAction.edge_async(this::routeAfterSqlCheck), Map.of(
                "executeReadOnlyQuery", "executeReadOnlyQuery",
                "generateSql", "generateSql",
                "END", StateGraph.END));
        // A failed query execution is terminal: never feed a null result into
        // the chart node (which would raise a second, generic failure).
        graph.addConditionalEdges("executeReadOnlyQuery", AsyncEdgeAction.edge_async(this::routeAfterQueryExecution),
                Map.of("buildChartSpec", "buildChartSpec", "END", StateGraph.END));
        graph.addEdge("buildChartSpec", "summarizeFromResult");
        graph.addEdge("summarizeFromResult", StateGraph.END);
        return graph.compile();
    }

    /**
     * Shared routing for both SQL check nodes; the context status carries which
     * check passed: OK → cost check next, OK_COST → execution next.
     */
    private String routeAfterSqlCheck(OverAllState state) {
        RunContext ctx = contexts.get(runId(state));
        if (ctx == null) {
            return "END";
        }
        return switch (ctx.status) {
            case "OK" -> "explainAndCheckCost";
            case "OK_COST" -> "executeReadOnlyQuery";
            case "RETRY_SQL" -> "generateSql";
            default -> "END";
        };
    }

    /** Routing after the read-only execution: failures end the run immediately. */
    private String routeAfterQueryExecution(OverAllState state) {
        RunContext ctx = contexts.get(runId(state));
        if (ctx == null || "FAILED".equals(ctx.status)) {
            return "END";
        }
        return "buildChartSpec";
    }

    /** Runs the full workflow for one question; blocks until the terminal event. */
    public AnalysisRunResult run(UUID runId, String question) {
        return run(runId, question, null);
    }

    /** Runs the workflow with a pinned understanding (operator's structured clarification). */
    public AnalysisRunResult run(UUID runId, String question,
                                 AnalyticsModelClient.QuestionUnderstanding pinnedUnderstanding) {
        RunContext ctx = new RunContext();
        ctx.pinnedUnderstanding = pinnedUnderstanding;
        contexts.put(runId, ctx);
        // Lifecycle registration (RUN_STARTED/RESUMED) and every terminal event
        // are owned by OperationsAnalysisService: registration happens before
        // the queued task exposes the run ID, and terminal publication happens
        // only after the outcome wins the lifecycle transition — so a racing
        // timeout can never pair a completed trace with a failed status.
        try {
            Flux.from(compiled.stream(Map.of(
                            STATE_QUESTION, question,
                            STATE_RUN_ID, runId.toString())))
                    .blockLast(executionTimeout);
        } catch (RuntimeException exception) {
            ctx.status = "FAILED";
            ctx.failureStage = "ANALYSIS_ABORTED";
            return AnalysisRunResult.failed(runId, ctx.failureStage);
        } finally {
            // Unconditional removal: even a raced terminal publish must never
            // leak the run context (which can retain result rows).
            contexts.remove(runId);
        }
        return buildOutcome(runId, ctx);
    }

    /** Test visibility: proves no run context leaks after terminal paths. */
    int trackedContextCount() {
        return contexts.size();
    }

    private AnalysisRunResult buildOutcome(UUID runId, RunContext ctx) {
        switch (ctx.status) {
            case "NEEDS_CLARIFICATION" -> {
                // A clarification pause is not terminal: the run resumes on the
                // same ID, so the trace must stay open (a terminal event would
                // close the publisher and break every later resumption).
                publish(ctx, runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.PAUSED,
                        ExecutionStatus.NEEDS_CLARIFICATION, "需要澄清后再继续", null);
                return AnalysisRunResult.needsClarification(runId, ctx.clarificationQuestions,
                        List.copyOf(ctx.clarificationOptions), ctx.understanding);
            }
            case "FAILED" -> {
                // Terminal publication is owned by the service after the
                // outcome wins the lifecycle transition; the graph only records state.
                return AnalysisRunResult.failed(runId, ctx.failureStage);
            }
            default -> {
                return new AnalysisRunResult(runId, RunOutcome.COMPLETED, List.of(), List.of(),
                        ctx.chart, ctx.result, ctx.summary, null);
            }
        }
    }

    // ---- nodes -------------------------------------------------------------

    Map<String, Object> understandQuestion(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.UNDERSTANDING, "理解问题");
        String question = text(state, STATE_QUESTION);
        // Clarified runs carry the operator's structured selection as the understanding;
        // the model is not consulted again for metric resolution.
        ctx.understanding = ctx.pinnedUnderstanding != null
                ? ctx.pinnedUnderstanding
                : modelClient.understandQuestion(question);
        if (ctx.understanding.needsClarification()) {
            ctx.clarificationQuestions.addAll(ctx.understanding.clarificationQuestions());
            // No structured candidates from the model: the operator picks from
            // the full catalog for each pending question.
            List<String> allNames = catalog.all().stream().map(m -> m.name()).toList();
            for (int i = 0; i < ctx.understanding.clarificationQuestions().size(); i++) {
                ctx.clarificationOptions.add(allNames);
            }
            ctx.status = "NEEDS_CLARIFICATION";
        }
        nodeCompleted(ctx, runId, ExecutionStage.UNDERSTANDING, "问题理解完成",
                DisplayPayload.text(ctx.understanding.normalizedQuestion(), false));
        return Map.of();
    }

    Map<String, Object> resolveMetricAndDimensions(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.PLANNING, "解析指标与维度");
        List<com.example.smartpark.analytics.catalog.MetricDefinition> selected = new ArrayList<>();
        for (String term : ctx.understanding.metricTerms()) {
            MetricResolution resolution = catalog.resolve(term);
            if (resolution instanceof MetricResolution.Resolved resolved) {
                selected.add(resolved.metric());
            } else if (resolution instanceof MetricResolution.Ambiguous ambiguous) {
                ctx.clarificationOptions.add(ambiguous.candidates().stream().map(m -> m.name()).toList());
                ctx.clarificationQuestions.add("“" + term + "”可以指: "
                        + ambiguous.candidates().stream()
                                .map(m -> m.displayName() + "(" + m.name() + ")")
                                .reduce((a, b) -> a + " / " + b).orElse("") + "，请明确指标口径");
            } else {
                // Unknown term: the operator may choose any catalog metric.
                ctx.clarificationOptions.add(catalog.all().stream().map(m -> m.name()).toList());
                ctx.clarificationQuestions.add("无法识别指标 “" + term + "”，请从指标目录中选择");
            }
        }
        if (!ctx.clarificationQuestions.isEmpty()) {
            ctx.status = "NEEDS_CLARIFICATION";
            nodeCompleted(ctx, runId, ExecutionStage.PLANNING, "需要澄清指标口径",
                    DisplayPayload.text(String.join("; ", ctx.clarificationQuestions), false));
            return Map.of();
        }
        ctx.metrics = List.copyOf(selected);
        nodeCompleted(ctx, runId, ExecutionStage.PLANNING, "指标解析完成: "
                + selected.stream().map(m -> m.name()).reduce((a, b) -> a + ", " + b).orElse(""), null);
        return Map.of();
    }

    Map<String, Object> recallAllowedSchema(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.PLANNING, "召回白名单 Schema");
        LinkedHashSet<String> views = new LinkedHashSet<>();
        StringBuilder description = new StringBuilder();
        for (var metric : ctx.metrics) {
            if (views.add(metric.sourceView())) {
                description.append(metric.sourceView())
                        .append(" 维度: ").append(String.join(", ", metric.allowedDimensions()));
                if (metric.condition() != null) {
                    description.append(" 固定条件: ").append(metric.condition());
                }
                description.append('\n');
            }
        }
        ctx.schemaDescription = description.toString();
        nodeCompleted(ctx, runId, ExecutionStage.PLANNING, "Schema 召回完成", null);
        return Map.of();
    }

    Map<String, Object> buildQueryPlan(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.PLANNING, "构建查询计划");
        Instant now = Instant.now(clock);
        int lookbackDays = ctx.metrics.stream().mapToInt(m -> m.defaultLookbackDays()).max().orElse(7);
        AnalyticsModelClient.RequestedTimeRange requested = ctx.understanding.requestedTimeRange();
        QueryPlan.TimeRange timeRange = requested == null
                ? new QueryPlan.TimeRange(now.minus(Duration.ofDays(lookbackDays)), now)
                : new QueryPlan.TimeRange(requested.fromInclusive(), requested.toExclusive());
        ctx.plan = new QueryPlan(
                ctx.understanding.normalizedQuestion(),
                ctx.metrics,
                validatedRequestedDimensions(ctx),
                Map.of(),
                timeRange,
                200);
        // One shared binding set for both gates: :fromTs/:toTs are the only
        // supported time boundaries and travel as bound parameters end to end.
        ctx.parameters = Map.of(
                "fromTs", java.sql.Timestamp.from(ctx.plan.timeRange().from()),
                "toTs", java.sql.Timestamp.from(ctx.plan.timeRange().to()));
        nodeCompleted(ctx, runId, ExecutionStage.PLANNING, "查询计划就绪", null);
        return Map.of();
    }

    private static List<String> validatedRequestedDimensions(RunContext ctx) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String dimension : ctx.understanding.requestedDimensions()) {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("请求维度不能为空");
            }
            String normalized = dimension.strip().toLowerCase(java.util.Locale.ROOT);
            boolean allowedByEveryMetric = ctx.metrics.stream()
                    .allMatch(metric -> metric.allowedDimensions().stream()
                            .anyMatch(allowed -> allowed.equalsIgnoreCase(normalized)));
            if (!allowedByEveryMetric) {
                throw new IllegalArgumentException("请求维度未获所有指标目录批准: " + dimension);
            }
            requested.add(normalized);
        }
        return List.copyOf(requested);
    }

    Map<String, Object> generateSql(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.ANALYSIS, "生成 SQL");
        ctx.sqlDraft = modelClient.generateSql(new AnalyticsModelClient.SqlGenerationRequest(
                ctx.plan, ctx.schemaDescription, ctx.rejectionReason));
        ctx.rejectionReason = null;
        nodeCompleted(ctx, runId, ExecutionStage.ANALYSIS, "SQL 草案生成", null);
        // A model draft is untrusted until both AST and plan validation pass.
        // Publish only a lifecycle marker here; SQL text first enters the event
        // stream in SQL_VALIDATED below.
        publish(ctx, runId, ExecutionStage.ANALYSIS, ExecutionEventType.SQL_GENERATED,
                ExecutionStatus.RUNNING, "SQL 草案已生成",
                DisplayPayload.text("SQL 草案已生成，等待安全校验", false));
        return Map.of();
    }

    Map<String, Object> validateSqlAst(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.SQL_VALIDATION, "AST 安全校验");
        try {
            ctx.validatedSql = SqlAstGuardAccess.validate(ctx.sqlDraft);
            com.example.smartpark.analytics.sql.SqlPlanGuard.validate(ctx.validatedSql, ctx.plan);
            for (String name : ctx.validatedSql.namedParameters()) {
                if (!ctx.parameters.containsKey(name)) {
                    throw new UnsafeSqlException("SQL_POLICY_REJECTED",
                            "使用了未提供绑定值的时间参数，仅允许 :fromTs 与 :toTs");
                }
            }
            ctx.status = "OK";
            nodeCompleted(ctx, runId, ExecutionStage.SQL_VALIDATION, "SQL 通过 AST 校验",
                    new DisplayPayload.SqlPayload(ctx.validatedSql.sql(),
                            ctx.validatedSql.namedParameters(), "PASSED"));
            publish(ctx, runId, ExecutionStage.SQL_VALIDATION, ExecutionEventType.SQL_VALIDATED,
                    ExecutionStatus.RUNNING, "SQL 校验通过",
                    new DisplayPayload.SqlPayload(ctx.validatedSql.sql(),
                            ctx.validatedSql.namedParameters(), "PASSED"));
        } catch (UnsafeSqlException rejection) {
            handleSqlRejection(ctx, runId, "validateSqlAst", rejection.getMessage());
        }
        return Map.of();
    }

    Map<String, Object> explainAndCheckCost(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.SQL_VALIDATION, "EXPLAIN 成本检查");
        try {
            costGate.check(ctx.validatedSql, ctx.parameters);
            ctx.status = "OK_COST";
            nodeCompleted(ctx, runId, ExecutionStage.SQL_VALIDATION, "成本检查通过", null);
        } catch (UnsafeSqlException rejection) {
            handleSqlRejection(ctx, runId, "explainAndCheckCost", rejection.getMessage());
        }
        return Map.of();
    }

    private void handleSqlRejection(RunContext ctx, UUID runId, String stage, String safeMessage) {
        // Rejections surface on the dedicated declared event type before the
        // retry (or terminal failure) proceeds.
        publish(ctx, runId, ExecutionStage.SQL_VALIDATION, ExecutionEventType.SQL_REJECTED,
                ExecutionStatus.RUNNING, "SQL 被拒绝: " + safeMessage,
                DisplayPayload.error(ExecutionStage.SQL_VALIDATION, "SQL_POLICY_REJECTED", false,
                        safeMessage == null ? "" : safeMessage));
        if (ctx.sqlAttempts >= MAX_SQL_ATTEMPTS - 1) {
            ctx.status = "FAILED";
            ctx.failureStage = stage;
            // Terminal publication is owned by the service after persistence.
        } else {
            ctx.sqlAttempts++;
            ctx.rejectionReason = safeMessage;
            ctx.status = "RETRY_SQL";
            nodeCompleted(ctx, runId, ExecutionStage.SQL_VALIDATION,
                    "SQL 被拒绝，允许一次修复: " + safeMessage, null);
        }
    }

    Map<String, Object> executeReadOnlyQueryNode(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.QUERY_EXECUTION, "只读执行查询");
        try {
            ctx.result = executionGate.execute(ctx.validatedSql, ctx.parameters);
            ctx.status = "OK_RESULT";
            nodeCompleted(ctx, runId, ExecutionStage.QUERY_EXECUTION,
                    "查询完成: " + ctx.result.rowCount() + " 行" + (ctx.result.truncated() ? "（已截断）" : ""),
                    DisplayPayload.text("返回 " + ctx.result.rowCount() + " 行数据", false));
            publish(ctx, runId, ExecutionStage.QUERY_EXECUTION, ExecutionEventType.QUERY_EXECUTED,
                    ExecutionStatus.RUNNING,
                    "查询执行完成: " + ctx.result.rowCount() + " 行",
                    DisplayPayload.text("返回 " + ctx.result.rowCount() + " 行数据"
                            + (ctx.result.truncated() ? "（已按上限截断）" : ""), false));
        } catch (UnsafeSqlException failure) {
            ctx.status = "FAILED";
            ctx.failureStage = "executeReadOnlyQuery";
            // Terminal publication is owned by the service after persistence.
        }
        return Map.of();
    }

    Map<String, Object> buildChartSpecNode(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.RENDERING, "生成图表规格");
        ChartSpec.Proposal proposal = null;
        try {
            proposal = modelClient.proposeChart(new AnalyticsModelClient.ChartContext(
                    strip(text(state, STATE_QUESTION)), ctx.plan, ctx.result));
        } catch (RuntimeException modelFailure) {
            // degrade below
        }
        ctx.chart = ChartSpec.fromProposal(proposal, ctx.result);
        // The chart payload travels on the dedicated CHART_SPECIFIED event type
        // defined by the public execution contract; the frontend captures it there.
        publish(ctx, runId, ExecutionStage.RENDERING, ExecutionEventType.CHART_SPECIFIED,
                ExecutionStatus.RUNNING, "图表规格: " + ctx.chart.type(),
                new DisplayPayload.ChartPayload(ctx.chart.type().name(), ctx.chart.title(), ctx.chart.xField(),
                        ctx.chart.yFields(), ctx.chart.seriesField(), ctx.chart.unit()));
        return Map.of();
    }

    Map<String, Object> summarizeFromResult(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.RESPONSE_DELIVERY, "基于结果总结");
        try {
            String conclusion = modelClient.summarize(new AnalyticsModelClient.SummaryContext(
                    strip(text(state, STATE_QUESTION)), ctx.plan, ctx.result, ctx.chart));
            ctx.summary = summaryValidator.validate(conclusion, ctx.plan, ctx.result);
            nodeCompleted(ctx, runId, ExecutionStage.RESPONSE_DELIVERY, "结论生成完成",
                    DisplayPayload.text(ctx.summary, false));
        } catch (RuntimeException summaryFailure) {
            // Conclusion failures keep SQL + result table alive without a summary.
            nodeCompleted(ctx, runId, ExecutionStage.RESPONSE_DELIVERY, "结论生成失败，保留 SQL 与结果表", null);
        }
        ctx.status = "COMPLETED";
        return Map.of();
    }

    // ---- helpers -----------------------------------------------------------

    private boolean needsClarification(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        return ctx != null && "NEEDS_CLARIFICATION".equals(ctx.status);
    }

    private UUID runId(OverAllState state) {
        return UUID.fromString(state.value(STATE_RUN_ID, String.class).orElseThrow());
    }

    private static String text(OverAllState state, String key) {
        return state.value(key, String.class).orElse("");
    }

    private void nodeStarted(RunContext ctx, UUID runId, ExecutionStage stage, String summary) {
        publish(ctx, runId, stage, ExecutionEventType.NODE_STARTED, ExecutionStatus.RUNNING, summary, null);
    }

    private void nodeCompleted(RunContext ctx, UUID runId, ExecutionStage stage, String summary, DisplayPayload payload) {
        publish(ctx, runId, stage, ExecutionEventType.NODE_COMPLETED, ExecutionStatus.RUNNING, summary, payload);
    }

    private void publish(RunContext ignoredCtx, UUID runId, ExecutionStage stage, ExecutionEventType type,
                         ExecutionStatus status, String summary, DisplayPayload payload) {
        publisher.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(clock),
                ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", stage, type, status,
                summary == null ? "" : summary, payload));
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Indirection over the static guard keeping JSqlParser details out of the graph.
     */
    private static final class SqlAstGuardAccess {
        static ValidatedSql validate(String sql) throws UnsafeSqlException {
            return com.example.smartpark.analytics.sql.SqlAstGuard.validate(sql);
        }
    }
}
