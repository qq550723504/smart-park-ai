package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.ValidatedSql;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.TimezoneExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Enforces that a safe SELECT structurally implements its approved query plan. */
public final class SqlPlanGuard {

    private SqlPlanGuard() {
    }

    public static void validate(ValidatedSql validatedSql, QueryPlan plan) throws UnsafeSqlException {
        // LIMIT is part of the approved result contract, not merely a ceiling.
        // A smaller LIMIT can silently omit planned entities just as a wider one
        // can widen the result set.
        if (validatedSql.maxRows() != plan.limit()) {
            throw reject("LIMIT 必须与查询计划完全一致: " + plan.limit());
        }
        Statement statement = parse(validatedSql.sql());
        if (!(statement instanceof Select select)) {
            throw reject("查询计划只能应用于 SELECT");
        }

        validateSourceGrain(plan);
        PlainSelect resultQuery = validateSupportedShape(select);
        List<Expression> terms = new ArrayList<>();
        flattenAnd(resultQuery.getWhere(), terms);
        List<Branch> branches = List.of(new Branch(
                resultQuery, List.copyOf(terms), List.of(SqlRelationName.from((Table) resultQuery.getFromItem()))));
        validateSourceOccurrences(branches, plan);
        validateMetricPredicateScopes(plan);

        for (MetricDefinition metric : plan.metrics()) {
            List<Branch> sourceBranches = branches.stream()
                    .filter(branch -> branch.tables().contains(SqlRelationName.parseCatalogName(metric.sourceView())))
                    .toList();
            if (sourceBranches.isEmpty()) {
                throw reject("查询缺少计划要求的数据视图 " + metric.sourceView());
            }
            if (!sourceBranches.stream().anyMatch(branch -> hasLowerBound(branch.terms(), metric.timeColumn()))) {
                throw reject("时间列 " + metric.timeColumn() + " 缺少 :fromTs 包含下界");
            }
            if (!sourceBranches.stream().anyMatch(branch -> hasUpperBound(branch.terms(), metric.timeColumn()))) {
                throw reject("时间列 " + metric.timeColumn() + " 缺少 :toTs 排除上界");
            }
            if (metric.condition() != null) {
                boolean conditionPresent = false;
                for (Branch branch : sourceBranches) {
                    if (hasFixedCondition(branch.terms(), metric.condition())) {
                        conditionPresent = true;
                        break;
                    }
                }
                if (!conditionPresent) throw reject("查询缺少指标的固定条件，请按口径过滤");
            }
        }
        validateCompletePredicates(branches, plan);
        validateProjection(resultQuery, plan);
    }

    /**
     * A QueryPlan currently has one shared dimension list but no join-cardinality
     * proof or per-source aggregation contract. Combining different fact views
     * would therefore make row multiplication unverifiable. Refuse that plan
     * shape until the plan model can represent independently aggregated sources.
     */
    private static void validateSourceGrain(QueryPlan plan) throws UnsafeSqlException {
        long sourceViews = plan.metrics().stream()
                .map(metric -> SqlRelationName.parseCatalogName(metric.sourceView()))
                .distinct()
                .count();
        if (sourceViews > 1) {
            throw reject("多事实视图查询缺少可验证的 source grain，禁止合并原始事实行");
        }
    }

    private static void validateSourceOccurrences(List<Branch> branches, QueryPlan plan)
            throws UnsafeSqlException {
        Set<SqlRelationName> planned = plan.metrics().stream()
                .map(metric -> SqlRelationName.parseCatalogName(metric.sourceView()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<SqlRelationName> physicalOccurrences = branches.stream()
                .flatMap(branch -> branch.tables().stream())
                .filter(SqlRelationName::isQualified)
                .toList();
        if (physicalOccurrences.size() != 1 || !planned.contains(physicalOccurrences.get(0))) {
            List<String> requiredViews = plan.metrics().stream()
                    .map(MetricDefinition::sourceView)
                    .distinct()
                    .toList();
            throw reject("查询的物理 source occurrence 必须与单事实计划完全一致: " + requiredViews);
        }
    }

    /**
     * Fixed catalog predicates currently apply to a whole source branch. If
     * metrics sharing that branch require different predicates, one metric's
     * filter would silently change every other metric. Such combinations need
     * a future per-metric conditional-aggregation plan and are refused today.
     */
    private static void validateMetricPredicateScopes(QueryPlan plan) throws UnsafeSqlException {
        Map<String, Set<String>> conditionsByView = new HashMap<>();
        for (MetricDefinition metric : plan.metrics()) {
            String condition = metric.condition() == null
                    ? "<none>" : canonical(parseCondition(metric.condition()));
            conditionsByView.computeIfAbsent(metric.sourceView().toLowerCase(Locale.ROOT), ignored -> new HashSet<>())
                    .add(condition);
        }
        if (conditionsByView.values().stream().anyMatch(conditions -> conditions.size() > 1)) {
            throw reject("指标组合需要独立的 metric-specific predicate，不能共享分支级固定条件");
        }
    }

    private static Expression parseCondition(String condition) throws UnsafeSqlException {
        try {
            return CCJSqlParserUtil.parseCondExpression(condition);
        } catch (Exception exception) {
            throw reject("指标目录包含无法解析的固定条件");
        }
    }

    private static void validateCompletePredicates(List<Branch> branches, QueryPlan plan)
            throws UnsafeSqlException {
        if (!plan.filters().isEmpty()) {
            throw reject("当前查询计划尚不支持普通维度过滤条件");
        }
        Set<SqlRelationName> plannedSources = plan.metrics().stream()
                .map(metric -> SqlRelationName.parseCatalogName(metric.sourceView()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Branch sourceBranch = branches.stream()
                .filter(branch -> branch.tables().stream().anyMatch(plannedSources::contains))
                .findFirst()
                .orElseThrow(() -> reject("查询缺少计划要求的数据视图"));
        List<Expression> remaining = new ArrayList<>(sourceBranch.terms());

        for (String timeColumn : plan.metrics().stream().map(MetricDefinition::timeColumn).distinct().toList()) {
            consumeOne(remaining, term -> isLowerBound(term, timeColumn),
                    "时间列 " + timeColumn + " 缺少 :fromTs 包含下界");
            consumeOne(remaining, term -> isUpperBound(term, timeColumn),
                    "时间列 " + timeColumn + " 缺少 :toTs 排除上界");
        }
        Set<String> fixedConditions = new LinkedHashSet<>();
        for (MetricDefinition metric : plan.metrics()) {
            if (metric.condition() != null) {
                fixedConditions.add(canonical(parseCondition(metric.condition())));
            }
        }
        for (String fixedCondition : fixedConditions) {
            consumeOne(remaining, term -> canonical(term).equals(fixedCondition),
                    "查询缺少指标的固定条件，请按口径过滤");
        }
        if (!remaining.isEmpty()) {
            throw reject("查询包含计划之外的结果谓词 " + remaining);
        }
    }

    private static void consumeOne(List<Expression> terms,
                                   Predicate<Expression> matches,
                                   String missingMessage) throws UnsafeSqlException {
        for (int index = 0; index < terms.size(); index++) {
            if (matches.test(terms.get(index))) {
                terms.remove(index);
                return;
            }
        }
        throw reject(missingMessage);
    }

    private static Statement parse(String sql) throws UnsafeSqlException {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception exception) {
            throw reject("已校验 SQL 无法重新解析查询计划");
        }
    }

    /**
     * QueryPlan can currently describe one physical fact source, projections,
     * predicates, grouping and an exact row bound. It cannot prove semantics
     * through relational transformations, so accept only that direct shape.
     */
    private static PlainSelect validateSupportedShape(Select select) throws UnsafeSqlException {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw reject("当前 QueryPlan 不支持 CTE 关系变换");
        }
        if (!(select instanceof PlainSelect plain)) {
            throw reject("当前 QueryPlan 不支持结果子查询");
        }
        if (!(plain.getFromItem() instanceof Table)) {
            throw reject("当前 QueryPlan 只支持直接白名单表，禁止 FROM 子查询");
        }
        if (plain.getJoins() != null && !plain.getJoins().isEmpty()) {
            throw reject("当前单事实 QueryPlan 不支持 JOIN");
        }
        if (plain.getHaving() != null) {
            throw reject("当前 QueryPlan 不支持 HAVING 结果谓词");
        }
        if (plain.getDistinct() != null) {
            throw reject("当前 QueryPlan 不支持 DISTINCT 结果变换");
        }
        if (plain.getOrderByElements() != null && !plain.getOrderByElements().isEmpty()) {
            throw reject("当前 QueryPlan 不支持 ORDER BY 结果变换");
        }
        if (plain.getOffset() != null
                || (plain.getLimit() != null && plain.getLimit().getOffset() != null)) {
            throw reject("当前 QueryPlan 不支持 OFFSET 结果变换");
        }
        if (plain.getFetch() != null) {
            throw reject("当前 QueryPlan 不支持 FETCH 结果变换");
        }
        return plain;
    }

    private record Branch(PlainSelect select, List<Expression> terms, List<SqlRelationName> tables) { }

    private static void validateProjection(PlainSelect resultQuery, QueryPlan plan)
            throws UnsafeSqlException {
        Map<String, List<String>> metricNamesByExpression = new HashMap<>();
        for (MetricDefinition metric : plan.metrics()) {
            try {
                String expression = canonicalProjection(CCJSqlParserUtil.parseExpression(metric.expression()));
                metricNamesByExpression.computeIfAbsent(expression, ignored -> new ArrayList<>())
                        .add(metric.name().toLowerCase(Locale.ROOT));
            } catch (Exception exception) {
                throw reject("指标目录包含无法解析的聚合表达式 " + metric.expression());
            }
        }
        Set<String> groupedColumns = groupedColumns(resultQuery, plan);
        Map<String, Integer> projectedMetricCounts = new HashMap<>();
        Set<String> usedMetricAliases = new LinkedHashSet<>();
        Set<String> projectedDimensions = new LinkedHashSet<>();
        List<String> invalidProjections = new ArrayList<>();
        for (var item : resultQuery.getSelectItems()) {
            Expression expression = item.getExpression();
            String canonical = canonicalProjection(expression);
            List<String> metricNames = metricNamesByExpression.get(canonical);
            if (metricNames != null) {
                projectedMetricCounts.merge(canonical, 1, Integer::sum);
                if (item.getAlias() != null) {
                    String alias = item.getAlias().getUnquotedName().toLowerCase(Locale.ROOT);
                    if (!metricNames.contains(alias)) {
                        throw reject("指标投影别名必须保持计划输出身份: " + alias);
                    }
                    if (!usedMetricAliases.add(alias)) {
                        throw reject("查询重复使用了指标投影别名 " + alias);
                    }
                } else if (metricNames.size() > 1) {
                    throw reject("同表达式指标必须使用计划指标名作为投影别名");
                }
                continue;
            }
            if (expression instanceof Column column) {
                String name = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
                if (!allowedDimensions(plan).contains(name)) {
                    throw reject("查询选择了未获指标目录批准的维度 " + column.getUnquotedColumnName());
                }
                // A projected non-aggregate dimension must be grouped, otherwise
                // PostgreSQL rejects the query during EXPLAIN and the analysis
                // dies as ANALYSIS_ABORTED instead of a repairable rejection.
                if (!groupedColumns.contains(name)) {
                    throw reject("投影的维度 " + column.getUnquotedColumnName() + " 必须出现在 GROUP BY 中");
                }
                if (!projectedDimensions.add(name)) {
                    throw reject("查询重复投影了维度 " + name);
                }
                if (item.getAlias() != null
                        && !item.getAlias().getUnquotedName().equalsIgnoreCase(name)) {
                    throw reject("维度投影别名必须保持计划输出身份: " + name);
                }
                continue;
            }
            invalidProjections.add(expression.toString());
        }
        for (Map.Entry<String, List<String>> expected : metricNamesByExpression.entrySet()) {
            int actualCount = projectedMetricCounts.getOrDefault(expected.getKey(), 0);
            if (actualCount != expected.getValue().size()) {
                throw reject("指标投影必须与计划完全一致: " + expected.getValue());
            }
            if (expected.getValue().size() > 1
                    && !usedMetricAliases.containsAll(expected.getValue())) {
                throw reject("同表达式指标投影别名缺少计划指标: " + expected.getValue());
            }
        }
        if (!invalidProjections.isEmpty()) {
            throw reject("查询包含未获指标目录批准的投影 " + invalidProjections);
        }
        Set<String> expectedDimensions = allowedDimensions(plan);
        if (!projectedDimensions.equals(expectedDimensions)) {
            Set<String> missing = new LinkedHashSet<>(expectedDimensions);
            missing.removeAll(projectedDimensions);
            throw reject("查询缺少计划要求的投影维度 " + missing);
        }
        if (!groupedColumns.equals(expectedDimensions)) {
            Set<String> missing = new LinkedHashSet<>(expectedDimensions);
            missing.removeAll(groupedColumns);
            throw reject("GROUP BY 缺少计划要求的维度 " + missing);
        }
    }

    private static Set<String> groupedColumns(PlainSelect resultQuery, QueryPlan plan)
            throws UnsafeSqlException {
        Set<String> grouped = new LinkedHashSet<>();
        if (resultQuery.getGroupBy() != null) {
            var groupBy = resultQuery.getGroupBy().getGroupByExpressionList();
            for (int i = 0; i < groupBy.size(); i++) {
                Expression expression = (Expression) groupBy.get(i);
                if (!(expression instanceof Column column)) {
                    throw reject("GROUP BY 使用了未获指标目录批准的维度 " + expression);
                }
                String name = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
                if (!allowedDimensions(plan).contains(name)) {
                    throw reject("GROUP BY 使用了未获指标目录批准的维度 " + expression);
                }
                if (!grouped.add(name)) {
                    throw reject("GROUP BY 重复使用了维度 " + name);
                }
            }
        }
        return grouped;
    }

    private static Expression parseExpression(String expression) throws UnsafeSqlException {
        try {
            return CCJSqlParserUtil.parseExpression(expression);
        } catch (Exception exception) {
            throw reject("指标目录包含无法解析的聚合表达式");
        }
    }

    private static Set<String> allowedDimensions(QueryPlan plan) {
        Set<String> dimensions = new LinkedHashSet<>();
        plan.dimensions().forEach(value -> dimensions.add(value.toLowerCase(Locale.ROOT)));
        return dimensions;
    }

    private static String canonicalProjection(Expression expression) {
        return expression.toString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\b[a-z_][a-z0-9_]*\\.", "");
    }

    private static void flattenAnd(Expression expression, List<Expression> result) {
        Expression unwrapped = unwrap(expression);
        if (unwrapped instanceof AndExpression and) {
            flattenAnd(and.getLeftExpression(), result);
            flattenAnd(and.getRightExpression(), result);
        } else if (unwrapped != null) {
            result.add(unwrapped);
        }
    }

    private static boolean hasLowerBound(List<Expression> terms, String timeColumn) {
        return terms.stream().anyMatch(term -> isLowerBound(term, timeColumn));
    }

    private static boolean hasUpperBound(List<Expression> terms, String timeColumn) {
        return terms.stream().anyMatch(term -> isUpperBound(term, timeColumn));
    }

    private static boolean isLowerBound(Expression term, String timeColumn) {
        return comparison(term, GreaterThanEquals.class, timeColumn, "fromTs")
                || reversedComparison(term,
                        net.sf.jsqlparser.expression.operators.relational.MinorThanEquals.class,
                        "fromTs", timeColumn);
    }

    private static boolean isUpperBound(Expression term, String timeColumn) {
        return comparison(term, MinorThan.class, timeColumn, "toTs")
                || reversedComparison(term, GreaterThan.class, "toTs", timeColumn);
    }

    private static boolean comparison(Expression expression,
                                      Class<? extends BinaryExpression> type,
                                      String column,
                                      String parameter) {
        Expression term = unwrap(expression);
        return type.isInstance(term)
                && term instanceof BinaryExpression binary
                && isColumn(binary.getLeftExpression(), column)
                && isParameter(binary.getRightExpression(), parameter);
    }

    private static boolean reversedComparison(Expression expression,
                                              Class<? extends BinaryExpression> type,
                                              String parameter,
                                              String column) {
        Expression term = unwrap(expression);
        return type.isInstance(term)
                && term instanceof BinaryExpression binary
                && isParameter(binary.getLeftExpression(), parameter)
                && isColumn(binary.getRightExpression(), column);
    }

    private static boolean isColumn(Expression expression, String expected) {
        return unwrap(expression) instanceof Column column
                && column.getUnquotedColumnName().equalsIgnoreCase(expected);
    }

    private static boolean isParameter(Expression expression, String expected) {
        return unwrap(expression) instanceof JdbcNamedParameter parameter
                && parameter.getName().equals(expected);
    }

    private static boolean hasFixedCondition(List<Expression> actualTerms, String requiredSql)
            throws UnsafeSqlException {
        Expression required;
        try {
            required = CCJSqlParserUtil.parseCondExpression(requiredSql);
        } catch (Exception exception) {
            throw reject("指标目录包含无法解析的固定条件");
        }
        String requiredShape = canonical(required);
        return actualTerms.stream().map(SqlPlanGuard::canonical).anyMatch(requiredShape::equals);
    }

    private static String canonical(Expression expression) {
        Expression value = unwrap(expression);
        if (value instanceof Column column) {
            return "column:" + column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
        }
        if (value instanceof JdbcNamedParameter parameter) {
            return "parameter:" + parameter.getName();
        }
        if (value instanceof ExtractExpression extract) {
            return "extract:" + extract.getName().toLowerCase(Locale.ROOT)
                    + "(" + canonical(extract.getExpression()) + ")";
        }
        if (value instanceof TimezoneExpression timezone) {
            return "timezone:(" + canonical(timezone.getLeftExpression()) + ","
                    + timezone.getTimezoneExpressions().stream()
                            .map(SqlPlanGuard::canonical)
                            .toList() + ")";
        }
        if (value instanceof AndExpression || value instanceof OrExpression) {
            BinaryExpression binary = (BinaryExpression) value;
            List<String> sides = new ArrayList<>(List.of(
                    canonical(binary.getLeftExpression()), canonical(binary.getRightExpression())));
            sides.sort(Comparator.naturalOrder());
            return value.getClass().getSimpleName() + sides;
        }
        if (value instanceof EqualsTo equality) {
            List<String> sides = new ArrayList<>(List.of(
                    canonical(equality.getLeftExpression()), canonical(equality.getRightExpression())));
            sides.sort(Comparator.naturalOrder());
            return "EqualsTo" + sides;
        }
        if (value instanceof BinaryExpression binary) {
            return value.getClass().getSimpleName() + "(" + canonical(binary.getLeftExpression())
                    + "," + canonical(binary.getRightExpression()) + ")";
        }
        return value.getClass().getSimpleName() + ":" + value.toString().replaceAll("\\s+", "");
    }

    private static Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof ParenthesedExpressionList<?> parenthesized && parenthesized.size() == 1) {
            value = parenthesized.get(0);
        }
        return value;
    }

    private static UnsafeSqlException reject(String message) {
        return new UnsafeSqlException("SQL_POLICY_REJECTED", message);
    }
}
