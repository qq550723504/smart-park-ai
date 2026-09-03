package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricResolution;
import com.example.smartpark.analytics.catalog.CategoricalFilterVocabulary;
import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.UnsafeSqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ten-node analysis workflow on the native 2.0 StateGraph:
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

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationsAnalysisGraph.class);

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
    private final TimeIntentProvider timeIntentProvider;
    private final AnalyticsQuestionNormalizer questionNormalizer = new AnalyticsQuestionNormalizer();
    private final TimeEvidenceReconciler timeEvidenceReconciler = new TimeEvidenceReconciler();
    private final TimeConstraintResolver timeConstraintResolver = new TimeConstraintResolver();
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
            String failureStage,
            TimeResolutionMetadata timeResolution) {

        /** @deprecated 兼容旧签名；新代码应携带时间解析元数据。 */
        @Deprecated
        public AnalysisRunResult(UUID runId, RunOutcome outcome,
                                 List<String> clarificationQuestions,
                                 List<List<String>> clarificationOptions,
                                 ChartSpec chart, TabularResult result,
                                 String summary, String failureStage) {
            this(runId, outcome, clarificationQuestions, clarificationOptions,
                    chart, result, summary, null, failureStage, null);
        }

        /** @deprecated 兼容旧签名（含模型理解）；图谱不再读取其中的模型时间戳。 */
        @Deprecated
        public AnalysisRunResult(UUID runId, RunOutcome outcome,
                                 List<String> clarificationQuestions,
                                 List<List<String>> clarificationOptions,
                                 ChartSpec chart, TabularResult result,
                                 String summary,
                                 AnalyticsModelClient.QuestionUnderstanding understanding,
                                 String failureStage) {
            this(runId, outcome, clarificationQuestions, clarificationOptions,
                    chart, result, summary, understanding, failureStage, null);
        }

        static AnalysisRunResult failed(UUID runId, String stage) {
            return new AnalysisRunResult(runId, RunOutcome.FAILED, List.of(), List.of(), null, null, null, null, stage, null);
        }

        static AnalysisRunResult needsClarification(UUID runId, List<String> questions,
                                                    List<List<String>> options,
                                                    AnalyticsModelClient.QuestionUnderstanding understanding) {
            return new AnalysisRunResult(runId, RunOutcome.NEEDS_CLARIFICATION, questions, options,
                    null, null, null, understanding, null, null);
        }
    }

    private static final class RunContext {
        AnalyticsModelClient.QuestionUnderstanding understanding;
        AnalyticsModelClient.QuestionUnderstanding pinnedUnderstanding;
        List<com.example.smartpark.analytics.catalog.MetricDefinition> metrics = List.of();
        String schemaDescription = "";
        TimeIntentResult timeIntentResult;
        TimeResolutionMetadata timeResolution;
        Instant referenceInstant;
        QueryPlan.TimeRange serverTimeRange;
        QueryPlan.TimeRangeSource timeRangeSource;
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
        String currentNode;
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
                Duration.ofSeconds(60), failClosedProvider());
    }

    public OperationsAnalysisGraph(MetricCatalog catalog,
                                   AnalyticsModelClient modelClient,
                                   CostGate costGate,
                                   ExecutionGate executionGate,
                                   ExecutionEventPublisher publisher,
                                   AnalysisSummaryValidator summaryValidator,
                                   Clock clock,
                                   Duration executionTimeout) {
        this(catalog, modelClient, costGate, executionGate, publisher, summaryValidator, clock,
                executionTimeout, failClosedProvider());
    }

    public OperationsAnalysisGraph(MetricCatalog catalog,
                            AnalyticsModelClient modelClient,
                            CostGate costGate,
                            ExecutionGate executionGate,
                            ExecutionEventPublisher publisher,
                            AnalysisSummaryValidator summaryValidator,
                            Clock clock,
                            Duration executionTimeout,
                            TimeIntentProvider timeIntentProvider) {
        this.catalog = catalog;
        this.modelClient = modelClient;
        this.costGate = costGate;
        this.executionGate = executionGate;
        this.publisher = publisher == null ? new InMemoryExecutionEventPublisher() : publisher;
        this.summaryValidator = summaryValidator;
        this.clock = clock;
        this.timeIntentProvider = java.util.Objects.requireNonNull(timeIntentProvider, "timeIntentProvider");
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

    private static TimeIntentProvider failClosedProvider() {
        return (question, now) -> {
            throw new IllegalStateException("analytics time intent provider must be configured");
        };
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
            ctx.failureStage = ctx.failureStage == null
                    ? (ctx.currentNode == null ? "analysis" : ctx.currentNode)
                    : ctx.failureStage;
            LOGGER.warn("Operations analysis failed at {} ({}) for run {}",
                    ctx.failureStage, exception.getClass().getSimpleName(), runId);
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
        TimeResolutionMetadata timeResolution = ctx.timeResolution;
        switch (ctx.status) {
            case "NEEDS_CLARIFICATION" -> {
                // A clarification pause is not terminal: the run resumes on the
                // same ID, so the trace must stay open (a terminal event would
                // close the publisher and break every later resumption).
                publish(ctx, runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.PAUSED,
                        ExecutionStatus.NEEDS_CLARIFICATION, "需要澄清后再继续", null);
                return new AnalysisRunResult(runId, RunOutcome.NEEDS_CLARIFICATION,
                        ctx.clarificationQuestions, List.copyOf(ctx.clarificationOptions),
                        null, null, null, ctx.understanding, null, timeResolution);
            }
            case "FAILED" -> {
                // Terminal publication is owned by the service after the
                // outcome wins the lifecycle transition; the graph only records state.
                return AnalysisRunResult.failed(runId, ctx.failureStage);
            }
            default -> {
                if ("EMPTY".equals(ctx.status)) {
                    return new AnalysisRunResult(runId, RunOutcome.COMPLETED,
                            List.<String>of(), List.<List<String>>of(),
                            null, null, "当前周期刚开始，暂无数据", null, null, ctx.timeResolution);
                }
                return new AnalysisRunResult(runId, RunOutcome.COMPLETED,
                        List.of(), List.of(),
                        ctx.chart, ctx.result, ctx.summary, null, null, ctx.timeResolution);
            }
        }
    }

    // ---- nodes -------------------------------------------------------------

    Map<String, Object> understandQuestion(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        nodeStarted(ctx, runId, ExecutionStage.UNDERSTANDING, "理解问题");
        String question = text(state, STATE_QUESTION);
        // 澄清恢复携带操作员的结构化选择与服务器解析的时间快照；不再重复解析。
        AnalyticsModelClient.QuestionUnderstanding modelUnderstanding = ctx.pinnedUnderstanding != null
                ? ctx.pinnedUnderstanding
                : modelClient.understandQuestion(question);
        modelUnderstanding = questionNormalizer.normalize(question, modelUnderstanding);
        if (ctx.pinnedUnderstanding != null && ctx.pinnedUnderstanding.serverResolvedTimeRange() != null) {
            ctx.serverTimeRange = toTimeRange(ctx.pinnedUnderstanding.serverResolvedTimeRange());
            ctx.timeRangeSource = QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE;
            ctx.timeResolution = TimeResolutionMetadata.explicit(ctx.serverTimeRange.from(),
                    ctx.serverTimeRange.to(), "澄清确认的时间范围");
            ctx.understanding = modelUnderstanding;
            finishUnderstanding(ctx, runId);
            return Map.of();
        }
        // 单一参考时刻：识别、换算与快照共用同一时钟读数。
        Instant reference = ctx.pinnedUnderstanding != null && ctx.pinnedUnderstanding.serverReferenceInstant() != null
                ? ctx.pinnedUnderstanding.serverReferenceInstant() : Instant.now(clock);
        ctx.referenceInstant = reference;
        TimeIntentResult parserResult = timeIntentProvider.resolve(question, reference);
        TimeIntentResult finalTime = timeEvidenceReconciler.reconcile(
                parserResult, modelUnderstanding.requestedTimeMentions(), question);
        switch (finalTime.status()) {
            case UNSUPPORTED, AMBIGUOUS -> throw new IllegalArgumentException(
                    "原始问题包含暂不支持的时间范围表达式: " + finalTime.reason());
            case MULTIPLE -> throw new IllegalArgumentException("原始问题包含多个时间范围，当前查询计划不支持范围对比");
            case EMPTY -> {
                ctx.status = "EMPTY";
                ctx.timeResolution = TimeResolutionMetadata.emptyPeriod(reference);
                ctx.understanding = modelUnderstanding;
                publish(ctx, runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.NODE_COMPLETED,
                        ExecutionStatus.RUNNING, "当前周期刚开始，暂无数据",
                        DisplayPayload.text("当前周期刚开始，暂无数据", false));
                return Map.of();
            }
            default -> { /* NONE / PARSED 继续走正常链路 */ }
        }
        ctx.timeResolution = finalTime.status() == TimeIntentResult.Status.PARSED
                ? TimeResolutionMetadata.explicit(finalTime.timeRange().from(), finalTime.timeRange().to(),
                        finalTime.mentions().stream()
                                .map(TimeIntentResult.TimeMention::text)
                                .reduce((a, b) -> a + "、" + b).orElse(""))
                : null;
        ctx.timeIntentResult = finalTime;
        QueryPlan.TimeRange parsedServerTimeRange = finalTime.timeRange();
        ctx.timeRangeSource = finalTime.status() == TimeIntentResult.Status.PARSED
                ? QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE
                : QueryPlan.TimeRangeSource.DEFAULT_METRIC_LOOKBACK;
        ctx.serverTimeRange = parsedServerTimeRange;
        // 保存服务器拥有的绝对区间快照，供澄清暂停后复用；模型值仅供参考。
        ctx.understanding = withServerTimeRange(modelUnderstanding, parsedServerTimeRange, reference);
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
        finishUnderstanding(ctx, runId);
        return Map.of();
    }

    private void finishUnderstanding(RunContext ctx, UUID runId) {
        nodeCompleted(ctx, runId, ExecutionStage.UNDERSTANDING, "问题理解完成",
                DisplayPayload.text(ctx.understanding.normalizedQuestion(), false));
        // 用户可见的时间来源卡片：只含安全字段，随理解完成事件下发。
        if (ctx.timeResolution != null) {
            publish(ctx, runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.NODE_COMPLETED,
                    ExecutionStatus.RUNNING, "时间范围已确定", ctx.timeResolution.toDisplayPayload());
        }
    }

    Map<String, Object> resolveMetricAndDimensions(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        String question = text(state, STATE_QUESTION);
        nodeStarted(ctx, runId, ExecutionStage.PLANNING, "解析指标与维度");
        LinkedHashSet<String> selectedNames = new LinkedHashSet<>();
        List<com.example.smartpark.analytics.catalog.MetricDefinition> selected = new ArrayList<>();
        for (String term : ctx.understanding.metricTerms()) {
            MetricResolution resolution = catalog.resolve(term);
            if (resolution instanceof MetricResolution.Resolved resolved) {
                if (metricMentionedInQuestion(resolved.metric(), question)) {
                    if (selectedNames.add(resolved.metric().name())) {
                        selected.add(resolved.metric());
                    }
                } else {
                    ctx.clarificationOptions.add(catalog.all().stream().map(m -> m.name()).toList());
                    ctx.clarificationQuestions.add("模型选择的指标 “" + resolved.metric().name()
                            + "” 未出现在原始问题术语中，请确认指标口径");
                }
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

    private static boolean metricMentionedInQuestion(
            com.example.smartpark.analytics.catalog.MetricDefinition metric, String question) {
        String normalizedQuestion = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(metric.name(), metric.displayName()), metric.aliases().stream())
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(normalizedQuestion::contains);
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
        Instant now = ctx.referenceInstant != null ? ctx.referenceInstant : Instant.now(clock);
        int lookbackDays = ctx.metrics.stream().mapToInt(m -> m.defaultLookbackDays()).max().orElse(7);
        String originalQuestion = text(state, STATE_QUESTION).strip();
        // The original question is the authority for time semantics. The model
        // may return a range using a different locale or boundary convention,
        // so never let that advisory value reject a valid request or widen the
        // SQL window. Derive every recognized range once from the server clock.
        TimeConstraintResolver.Resolved resolvedTime = ctx.serverTimeRange != null
                ? new TimeConstraintResolver.Resolved(ctx.serverTimeRange,
                        QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE)
                : timeConstraintResolver.resolve(ctx.timeIntentResult, now, lookbackDays);
        QueryPlan.TimeRange timeRange = resolvedTime.timeRange();
        QueryPlan.TimeRangeSource timeRangeSource = resolvedTime.source();
        ctx.serverTimeRange = timeRange;
        ctx.timeRangeSource = timeRangeSource;
        if (timeRangeSource == QueryPlan.TimeRangeSource.DEFAULT_METRIC_LOOKBACK) {
            // 默认回看期同样显式标注来源：NONE 不是“无信息”，而是可审计的决策，
            // 随最终结果持久化并在 UI 中展示。
            ctx.timeResolution = TimeResolutionMetadata.defaultLookback(
                    timeRange.from(), timeRange.to());
        }
        ctx.plan = new QueryPlan(
                originalQuestion,
                ctx.metrics,
                validatedRequestedDimensions(ctx, originalQuestion),
                validatedRequestedFilters(ctx, originalQuestion),
                timeRange,
                200,
                timeRangeSource);
        // One shared binding set travels through both gates and execution.
        // Entity values are never copied into SQL literals.
        java.util.LinkedHashMap<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("fromTs", java.sql.Timestamp.from(ctx.plan.timeRange().from()));
        parameters.put("toTs", java.sql.Timestamp.from(ctx.plan.timeRange().to()));
        ctx.plan.filters().forEach((dimension, value) ->
                parameters.put(QueryPlan.filterParameterName(dimension), value));
        ctx.parameters = java.util.Collections.unmodifiableMap(parameters);
        nodeCompleted(ctx, runId, ExecutionStage.PLANNING, "查询计划就绪", null);
        return Map.of();
    }

    private static List<String> validatedRequestedDimensions(RunContext ctx, String originalQuestion) {
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
            if (!dimensionMentionedInQuestion(normalized, originalQuestion)) {
                throw new IllegalArgumentException("请求维度未出现在原始问题意图中: " + dimension);
            }
            requested.add(normalized);
        }
        // A missing model dimension must not silently change an explicit
        // aggregation into a total. Infer only unambiguous aggregation
        // phrases ("按楼宇", "各楼宇", ...); entity mentions such as "B1楼宇"
        // remain filters, not grouping dimensions.
        for (String dimension : inferredAggregationDimensions(ctx, originalQuestion)) {
            requested.add(dimension);
        }
        return List.copyOf(requested);
    }

    private static Map<String, String> validatedRequestedFilters(RunContext ctx, String question) {
        java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
        for (var entry : ctx.understanding.requestedFilters().entrySet()) {
            String dimension = entry.getKey() == null ? "" : entry.getKey().strip().toLowerCase(java.util.Locale.ROOT);
            String value = canonicalCategoricalValue(dimension, entry.getValue());
            filters.put(dimension, value);
        }
        for (String dimension : List.of("status", "risk_level", "category")) {
            boolean allowedByEveryMetric = ctx.metrics.stream()
                    .allMatch(metric -> metric.allowedDimensions().stream()
                            .anyMatch(allowed -> allowed.equalsIgnoreCase(dimension)));
            if (!allowedByEveryMetric) continue;
            if (CategoricalFilterVocabulary.containsNegatedTerm(dimension, question)) {
                throw new IllegalArgumentException("暂不支持分类条件的否定表达: " + dimension);
            }
            Set<String> matches = CategoricalFilterVocabulary.matchingCanonicalValues(dimension, question);
            if (matches.size() > 1) {
                throw new IllegalArgumentException("原始问题包含多个分类值，无法安全生成单值过滤条件: " + dimension);
            }
            if (!filters.containsKey(dimension) && matches.size() == 1) {
                filters.put(dimension, matches.iterator().next());
            }
        }
        return java.util.Collections.unmodifiableMap(filters);
    }

    private static String canonicalCategoricalValue(String dimension, String value) {
        if (value == null) return null;
        return CategoricalFilterVocabulary.canonicalValue(dimension, value);
    }

    private static List<String> inferredAggregationDimensions(RunContext ctx, String question) {
        List<String> inferred = new ArrayList<>();
        // A metric's timeColumn controls its safe filter window, not the only
        // legal GROUP BY grain. The energy view exposes derived stat_date and
        // hour_of_day columns, so daily/hourly visualizations can aggregate
        // the hourly source without widening the time predicate.
        if (dailyAggregationMentionedInQuestion(question)) {
            addAllowedDimension(ctx, inferred, "stat_date", "日粒度");
        }
        if (hourlyAggregationMentionedInQuestion(question)) {
            addAllowedDimension(ctx, inferred, "hour_ts", "小时粒度");
        }
        if (containsAny(question == null ? "" : question.toLowerCase(java.util.Locale.ROOT), "热力图", "热力")) {
            addAllowedDimension(ctx, inferred, "stat_date", "热力图日期粒度");
        }
        if (timeAggregationMentionedInQuestion(question)
                && !dailyAggregationMentionedInQuestion(question)
                && !hourlyAggregationMentionedInQuestion(question)) {
            for (var metric : ctx.metrics) {
                String timeColumn = metric.timeColumn();
                addAllowedDimension(ctx, inferred, timeColumn, "时间粒度");
            }
        }
        List<String> dimensions = List.of(
                "building_id", "building_name", "meter_id", "hour_of_day", "day_of_week", "area_sqm",
                "map_x", "map_y", "risk_level", "category", "status", "device_type", "parking_zone");
        for (String dimension : dimensions) {
            if (!aggregationDimensionMentionedInQuestion(dimension, question)) continue;
            addAllowedDimension(ctx, inferred, dimension, "聚合维度");
        }
        return List.copyOf(inferred);
    }

    private static void addAllowedDimension(RunContext ctx, List<String> dimensions,
                                            String dimension, String grainLabel) {
        boolean allowedByEveryMetric = ctx.metrics.stream()
                .allMatch(metric -> metric.allowedDimensions().stream()
                        .anyMatch(allowed -> allowed.equalsIgnoreCase(dimension)));
        if (!allowedByEveryMetric) {
            throw new IllegalArgumentException("原始问题要求的" + grainLabel + "未获所有指标目录批准: " + dimension);
        }
        if (!dimensions.contains(dimension)) dimensions.add(dimension);
    }

    private static boolean aggregationDimensionMentionedInQuestion(String dimension, String question) {
        String normalized = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        return switch (dimension) {
            case "building_id" -> containsAny(normalized,
                    "按楼宇", "各楼宇", "每个楼宇", "分楼宇", "楼宇对比", "楼宇分布", "楼宇空间",
                    "楼宇排行", "楼宇热力", "楼宇构成",
                    "按楼栋", "各楼栋", "每栋", "各栋");
            case "building_name" -> containsAny(normalized,
                    "楼宇名称", "楼宇分布", "楼宇空间", "楼宇排行", "楼宇热力", "楼宇构成", "空间分布", "地图");
            case "meter_id" -> containsAny(normalized,
                    "按表计", "各表计", "每个表计", "分表计", "按电表", "各电表");
            case "hour_ts" -> containsAny(normalized,
                    "按小时", "逐时", "每小时", "逐小时", "小时趋势");
            case "hour_of_day" -> containsAny(normalized,
                    "小时热力", "分时", "时段");
            case "day_of_week" -> containsAny(normalized, "按星期", "各星期", "周几", "星期");
            case "area_sqm" -> containsAny(normalized, "按面积", "单位面积", "面积");
            case "map_x", "map_y" -> containsAny(normalized, "空间分布", "地图", "平面图", "位置分布");
            case "risk_level" -> containsAny(normalized, "按风险", "各风险", "按风险等级");
            case "category" -> containsAny(normalized, "按类别", "各类别", "按分类", "各分类", "按类型", "各类型",
                    "按告警类型", "各告警类型", "按告警类别", "各告警类别");
            case "status" -> containsAny(normalized, "按状态", "各状态");
            case "device_type" -> containsAny(normalized, "按设备类型", "各设备类型");
            case "parking_zone" -> containsAny(normalized, "按区域", "各区域", "按车区", "各车区", "各停车区域");
            default -> false;
        };
    }

    private static boolean timeAggregationMentionedInQuestion(String question) {
        return containsAny(question == null ? "" : question.toLowerCase(java.util.Locale.ROOT),
                "按日", "每天", "每日", "按日期", "按时间", "按小时", "逐时", "每小时", "逐小时", "小时趋势");
    }

    private static boolean dailyAggregationMentionedInQuestion(String question) {
        return containsAny(question == null ? "" : question.toLowerCase(java.util.Locale.ROOT),
                "按日", "每天", "每日", "按日期", "日历");
    }

    private static boolean hourlyAggregationMentionedInQuestion(String question) {
        return containsAny(question == null ? "" : question.toLowerCase(java.util.Locale.ROOT),
                "按小时", "逐时", "每小时", "逐小时", "小时趋势");
    }

    private static boolean dimensionMentionedInQuestion(String dimension, String question) {
        String normalized = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        return switch (dimension) {
            case "building_id" -> containsAny(normalized, "building", "楼宇", "楼栋", "建筑", "栋", "各楼");
            case "building_name" -> containsAny(normalized, "building", "楼宇", "楼栋", "建筑", "栋", "各楼", "名称");
            case "meter_id" -> containsAny(normalized, "meter", "表计", "电表", "表号");
            case "hour_ts" -> containsAny(normalized, "hour", "小时", "逐时", "按小时", "每小时");
            case "hour_of_day" -> containsAny(normalized, "hour", "小时", "逐时", "按小时", "每小时", "分时", "时段");
            case "day_of_week" -> containsAny(normalized, "星期", "周几", "周");
            case "area_sqm" -> containsAny(normalized, "面积", "单位面积");
            case "map_x", "map_y" -> containsAny(normalized, "空间", "地图", "平面图", "位置");
            case "occurred_at" -> containsAny(normalized, "occurred", "发生时间", "时间", "按日", "日期");
            case "snapshot_at" -> containsAny(normalized, "snapshot", "快照", "时间");
            case "stat_date" -> containsAny(normalized, "stat", "日期", "按日", "每天", "每日");
            case "risk_level" -> containsAny(normalized, "risk", "风险", "风险等级");
            case "category" -> containsAny(normalized, "category", "类别", "分类", "类型");
            case "status" -> containsAny(normalized, "status", "状态");
            case "device_type" -> containsAny(normalized, "device", "设备", "设备类型");
            case "parking_zone" -> containsAny(normalized, "parking", "停车", "车区", "区域");
            default -> normalized.contains(dimension.toLowerCase(java.util.Locale.ROOT));
        };
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    private static QueryPlan.TimeRange toTimeRange(AnalyticsModelClient.RequestedTimeRange requested) {
        return new QueryPlan.TimeRange(requested.fromInclusive(), requested.toExclusive());
    }

    private static AnalyticsModelClient.QuestionUnderstanding withServerTimeRange(
            AnalyticsModelClient.QuestionUnderstanding understanding, QueryPlan.TimeRange timeRange,
            Instant referenceInstant) {
        AnalyticsModelClient.RequestedTimeRange requested = timeRange == null ? null
                : new AnalyticsModelClient.RequestedTimeRange(timeRange.from(), timeRange.to());
        return new AnalyticsModelClient.QuestionUnderstanding(
                understanding.normalizedQuestion(), understanding.metricTerms(), understanding.clarificationQuestions(),
                requested, understanding.requestedDimensions(), understanding.requestedFilters(),
                understanding.requestedTimeMentions(), requested, referenceInstant);
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
                            "使用了查询计划未提供的绑定参数");
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
        ctx.chart = ChartSpec.fromProposal(proposal, ctx.result, unitByColumn(ctx.plan));
        // The model is advisory. If it returns a table for an explicit chart
        // intent, select a deterministic shape from the executed columns and
        // run it through the same contract validator. This keeps rendering
        // useful when the model omits a chart type without inventing data.
        if (ctx.chart.type() == ChartSpec.ChartType.TABLE
                && ChartSpec.hasVisualizationIntent(text(state, STATE_QUESTION))) {
            ChartSpec recommended = ChartSpec.recommended(text(state, STATE_QUESTION), ctx.result,
                    unitByColumn(ctx.plan));
            if (recommended.type() != ChartSpec.ChartType.TABLE) ctx.chart = recommended;
        }
        // The chart payload travels on the dedicated CHART_SPECIFIED event type
        // defined by the public execution contract; the frontend captures it there.
        publish(ctx, runId, ExecutionStage.RENDERING, ExecutionEventType.CHART_SPECIFIED,
                ExecutionStatus.RUNNING, "图表规格: " + ctx.chart.type(),
                new DisplayPayload.ChartPayload(ctx.chart.type().name(), ctx.chart.title(), ctx.chart.xField(),
                        ctx.chart.yFields(), ctx.chart.seriesField(), ctx.chart.unit(),
                        ctx.chart.options().orientation(), ctx.chart.options().stacked(),
                        ctx.chart.options().targetValue(), ctx.chart.options().coordinateXField(),
                        ctx.chart.options().coordinateYField()));
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

    /**
     * Column→catalog-unit map derived from the executed plan. Chart unit
     * labels must come from these metric definitions, never from the model's
     * proposal — one wrong label would misread kWh as a percentage.
     */
    private static Map<String, String> unitByColumn(QueryPlan plan) {
        Map<String, String> unitByColumn = new java.util.LinkedHashMap<>();
        for (var metric : plan.metrics()) {
            unitByColumn.putIfAbsent(metric.name().toLowerCase(java.util.Locale.ROOT), metric.unit());
        }
        return unitByColumn;
    }

    private boolean needsClarification(OverAllState state) {
        UUID runId = runId(state);
        RunContext ctx = contexts.get(runId);
        // EMPTY 在理解节点即终止：不构造查询计划，不触碰 SQL。
        return ctx != null && ("NEEDS_CLARIFICATION".equals(ctx.status)
                || "EMPTY".equals(ctx.status));
    }

    private UUID runId(OverAllState state) {
        return UUID.fromString(state.value(STATE_RUN_ID, String.class).orElseThrow());
    }

    private static String text(OverAllState state, String key) {
        return state.value(key, String.class).orElse("");
    }

    private void nodeStarted(RunContext ctx, UUID runId, ExecutionStage stage, String summary) {
        ctx.currentNode = switch (summary) {
            case "理解问题" -> "understandQuestion";
            case "解析指标与维度" -> "resolveMetricAndDimensions";
            case "召回白名单 Schema" -> "recallAllowedSchema";
            case "构建查询计划" -> "buildQueryPlan";
            case "生成 SQL" -> "generateSql";
            case "AST 安全校验" -> "validateSqlAst";
            case "EXPLAIN 成本检查" -> "explainAndCheckCost";
            case "只读执行查询" -> "executeReadOnlyQuery";
            case "生成图表规格" -> "buildChartSpec";
            case "基于结果总结" -> "summarizeFromResult";
            default -> summary;
        };
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
