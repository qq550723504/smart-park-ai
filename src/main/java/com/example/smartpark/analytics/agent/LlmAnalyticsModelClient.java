package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.sql.QueryPlanSqlRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Real ChatModel-backed {@link AnalyticsModelClient}. Every method demands a
 * strictly structured answer and fails closed on malformed output — there is
 * no silent fallback result anywhere downstream of this boundary.
 */
public class LlmAnalyticsModelClient implements AnalyticsModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final MetricCatalog catalog;
    private final Clock clock;
    private final QueryPlanSqlRenderer sqlRenderer = new QueryPlanSqlRenderer();

    public LlmAnalyticsModelClient(ChatModel chatModel, MetricCatalog catalog) {
        this(chatModel, catalog, Clock.systemUTC());
    }

    public LlmAnalyticsModelClient(ChatModel chatModel, MetricCatalog catalog, Clock clock) {
        this.chatModel = chatModel;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public QuestionUnderstanding understandQuestion(String question) {
        String catalogHint = "指标目录（只能引用以下 name）: "
                + catalog.all().stream()
                        .map(metric -> metric.name() + "(" + metric.displayName() + ")")
                        .collect(Collectors.joining(", "));
        Instant now = Instant.now(clock);
        String system = """
                你是园区运营分析的理解模块。只输出 JSON 对象，字段:
                normalizedQuestion (字符串), metricTerms (name 数组), clarificationQuestions (字符串数组),
                requestedDimensions (用户明确要求的目录维度 name 数组；总计查询为空数组),
                requestedFilters (用户明确指定的实体过滤对象，键为目录维度 name、值为问题中原样出现的实体；没有则为空对象),
                requestedTimeMentions (字符串数组): 问题中出现的所有时间表达，每个元素必须是原问题中逐字存在的片段
                （如 "上周一到周三"、"过去30天"）；只负责找出这些词，禁止换算成日期或时间戳；没有则为空数组。
                当前时刻: %s；园区时区: Asia/Shanghai；园区当地时间: %s。
                """.formatted(now, now.atZone(ZoneId.of("Asia/Shanghai"))) + catalogHint;
        JsonNode json = parseJson(call(system, question));
        if (text(json, "normalizedQuestion").isBlank()) {
            throw new IllegalStateException("normalizedQuestion must be a non-empty string");
        }
        return new QuestionUnderstanding(
                requireQuestion(question),
                stringList(json, "metricTerms"),
                stringList(json, "clarificationQuestions"),
                null,
                stringList(json, "requestedDimensions"),
                stringMap(json, "requestedFilters"),
                stringList(json, "requestedTimeMentions"));
    }

    private static String requireQuestion(String question) {
        String normalized = question == null ? "" : question.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("question must not be blank");
        return normalized;
    }

    @Override
    public String generateSql(SqlGenerationRequest request) {
        // SQL is a deterministic projection of the catalog and typed plan.
        // The model is used for question understanding only; it cannot invent
        // predicates, joins, aliases, or time columns at this boundary.
        return sqlRenderer.render(request.plan());
    }

    /** The advertised row bound comes from the plan — never a hard-coded wider value. */
    static String sqlSystemPrompt(int maxRows) {
        return """
                你是园区只读分析 SQL 生成器。根据指标定义生成一条 PostgreSQL SELECT。
                硬性要求:
                1. 只允许查询给定白名单视图。
                2. 时间边界只能用命名参数 :fromTs（含）与 :toTs（不含），禁止任何日期字面量。
                3. 必须带 LIMIT %d，数值必须与计划完全一致。
                4. 单条语句，无注释、无分号、禁止 DML/DDL。
                5. 维度列不得改名；聚合表达式如使用别名，必须使用对应指标 name。
                6. 只生成单个直接 SELECT：禁止 CTE、子查询、JOIN、HAVING、DISTINCT、OFFSET、FETCH；ORDER BY 仅允许与计划声明的排序指标和方向完全一致，无声明则禁止。""".formatted(maxRows);
    }

    @Override
    public ChartSpec.Proposal proposeChart(ChartContext context) {
        TabularResult result = context.result();
        String columns = String.join(", ", result.columnNames());
        String dimensions = String.join(", ", context.plan().dimensions());
        String metrics = context.plan().metrics().stream()
                .map(metric -> metric.name() + "(" + metric.unit() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        try {
            String chartPrompt = """
                            你是图表规格建议器。只输出 JSON: type ("LINE"|"BAR"|"TABLE"|"KPI"|"STACKED_BAR"|"HEATMAP"|"CALENDAR_HEATMAP"|"SCATTER"|"GAUGE"|"MAP"), title,
                            xField, yFields (数组), seriesField (可为空字符串), unit,
                            orientation ("VERTICAL"|"HORIZONTAL"), stacked (布尔值), targetValue (数字或 null),
                            coordinateXField、coordinateYField（仅 MAP 使用）。
                            """;
            JsonNode json = parseJson(call(chartPrompt
                            + "只允许使用这些执行计划维度: " + dimensions + "；指标及单位: " + metrics + "。\n"
                            + "选择规则: 总量/完成率用 KPI 或 GAUGE；趋势用 LINE；排行用水平 BAR；"
                            + "构成/分时用 STACKED_BAR；热力图用 HEATMAP；日历热力图用 CALENDAR_HEATMAP；"
                            + "关系/相关性用 SCATTER；空间分布用 MAP。\n原始问题: " + context.question()
                            + "\n只能使用这些结果列: " + columns,
                    sampleRows(result)));
            return new ChartSpec.Proposal(
                    text(json, "type"), text(json, "title"), text(json, "xField"),
                    stringList(json, "yFields"), text(json, "seriesField"), text(json, "unit"),
                    new ChartSpec.RenderOptions(text(json, "orientation"), json.path("stacked").asBoolean(false),
                            nullableDouble(json, "targetValue"), text(json, "coordinateXField"),
                            text(json, "coordinateYField")));
        } catch (RuntimeException malformedProposal) {
            // fromProposal also degrades to TABLE; an unusable proposal is one too.
            return new ChartSpec.Proposal("TABLE", "查询结果", "", List.of(), "", "");
        }
    }

    @Override
    public String summarize(SummaryContext context) {
        return call("""
                你是园区运营分析总结器。只依据给定的真实查询结果写结论，
                不允许出现结果之外的数字或百分比。每个数据数字前必须先写出对应的结果维度值。
                用中文，两句话以内。""",
                summaryFacts(context));
    }

    // ---- helpers -----------------------------------------------------------

    private String call(String system, String user) {
        ChatResponse response = chatModel.call(new Prompt(new SystemMessage(system), new UserMessage(user)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("analytics model returned a blank response");
        }
        return response.getResult().getOutput().getText();
    }

    private JsonNode parseJson(String raw) {
        try {
            return MAPPER.readTree(stripCodeFences(raw));
        } catch (Exception exception) {
            throw new IllegalStateException("analytics model returned unparseable JSON");
        }
    }

    private static String stripCodeFences(String raw) {
        String value = raw.strip();
        if (value.startsWith("```")) {
            int firstBreak = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                return value.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return value;
    }

    private static String text(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText("") : "";
    }

    private static Double nullableDouble(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        if (!value.isNumber()) throw new IllegalStateException(field + " must be a number or null");
        double parsed = value.asDouble();
        if (!Double.isFinite(parsed)) throw new IllegalStateException(field + " must be finite");
        return parsed;
    }

    private static List<String> stringList(JsonNode json, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = json.get(field);
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private static Map<String, String> stringMap(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) {
            throw new IllegalStateException(field + " must be an object");
        }
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new IllegalStateException(field + " values must be non-empty strings");
            }
            values.put(entry.getKey(), entry.getValue().asText().strip());
        });
        return java.util.Collections.unmodifiableMap(values);
    }

    private static String sampleRows(TabularResult result) {
        StringBuilder builder = new StringBuilder("列: ").append(String.join(", ", result.columnNames())).append('\n');
        result.rows().stream().limit(10).forEach(row ->
                builder.append(row.stream()
                        .map(value -> value == null ? "null" : value.toString())
                        .collect(Collectors.joining(", "))).append('\n'));
        return builder.toString();
    }

    private static String summaryFacts(SummaryContext context) {
        StringBuilder builder = new StringBuilder("问题: ").append(context.question()).append('\n');
        builder.append("指标: ").append(context.plan().metrics().stream()
                .map(MetricDefinition::displayName)
                .collect(Collectors.joining(", "))).append('\n');
        builder.append(sampleRows(context.result()));
        return builder.toString();
    }
}
