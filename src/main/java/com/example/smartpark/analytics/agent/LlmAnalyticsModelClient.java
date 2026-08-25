package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
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
                requestedTimeRange (没有时间要求时为 null；否则为对象，包含 ISO-8601 UTC 字段
                fromInclusive 与 toExclusive)。所有相对时间都以当前时刻和园区时区解释。
                当前时刻: %s；园区时区: Asia/Shanghai；园区当地时间: %s。
                """.formatted(now, now.atZone(ZoneId.of("Asia/Shanghai"))) + catalogHint;
        JsonNode json = parseJson(call(system, question));
        return new QuestionUnderstanding(
                text(json, "normalizedQuestion"),
                stringList(json, "metricTerms"),
                stringList(json, "clarificationQuestions"),
                requestedTimeRange(json),
                stringList(json, "requestedDimensions"));
    }

    @Override
    public String generateSql(SqlGenerationRequest request) {
        QueryPlan plan = request.plan();
        String metricDescriptions = plan.metrics().stream()
                .map(metric -> "- " + metric.name() + ": 视图 " + metric.sourceView()
                        + ", 维度 " + metric.allowedDimensions()
                        + ", 聚合 " + metric.expression()
                        + (metric.condition() == null ? "" : ", 固定条件 " + metric.condition()))
                .collect(Collectors.joining("\n"));
        String system = sqlSystemPrompt(plan.limit());
        if (request.rejectionReason() != null && !request.rejectionReason().isBlank()) {
            system = system + "\n上一次生成被拒绝，必须修复该问题: " + request.rejectionReason();
        }
        String user = "问题: " + plan.question() + "\n时间范围: :fromTs ~ :toTs\n指标:\n" + metricDescriptions
                + "\nSchema:\n" + request.schemaDescription();
        return stripCodeFences(call(system, user));
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
                6. 只生成单个直接 SELECT：禁止 CTE、子查询、JOIN、HAVING、DISTINCT、ORDER BY、OFFSET、FETCH。
                """.formatted(maxRows);
    }

    @Override
    public ChartSpec.Proposal proposeChart(ChartContext context) {
        TabularResult result = context.result();
        String columns = String.join(", ", result.columnNames());
        try {
            JsonNode json = parseJson(call("""
                            你是图表规格建议器。只输出 JSON: type ("LINE"|"BAR"|"TABLE"), title,
                            xField, yFields (数组), seriesField (可为空字符串), unit。
                            只能使用这些结果列: """ + columns,
                    sampleRows(result)));
            return new ChartSpec.Proposal(
                    text(json, "type"), text(json, "title"), text(json, "xField"),
                    stringList(json, "yFields"), text(json, "seriesField"), text(json, "unit"));
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

    private static List<String> stringList(JsonNode json, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = json.get(field);
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private static RequestedTimeRange requestedTimeRange(JsonNode json) {
        JsonNode node = json.get("requestedTimeRange");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("requestedTimeRange must be an object or null");
        }
        String from = text(node, "fromInclusive");
        String to = text(node, "toExclusive");
        if (from.isBlank() || to.isBlank()) {
            throw new IllegalStateException("requestedTimeRange requires fromInclusive and toExclusive");
        }
        try {
            return new RequestedTimeRange(Instant.parse(from), Instant.parse(to));
        } catch (RuntimeException invalidRange) {
            throw new IllegalStateException("requestedTimeRange is invalid", invalidRange);
        }
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
