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
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.schema.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Enforces that a safe SELECT structurally implements its approved query plan. */
public final class SqlPlanGuard {

    private SqlPlanGuard() {
    }

    public static void validate(ValidatedSql validatedSql, QueryPlan plan) throws UnsafeSqlException {
        Statement statement = parse(validatedSql.sql());
        if (!(statement instanceof Select select)) {
            throw reject("查询计划只能应用于 SELECT");
        }

        PlainSelect resultQuery = resultQuery(select);
        List<Branch> branches = consumedBranches(select, resultQuery);
        validateSourceGrain(plan);
        validateConsumedBranchLineage(resultQuery, branches, plan);

        for (MetricDefinition metric : plan.metrics()) {
            List<Branch> sourceBranches = branches.stream()
                    .filter(branch -> branch.tables().contains(normalizeIdentifier(metric.sourceView())))
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
                .map(metric -> normalizeIdentifier(metric.sourceView()))
                .distinct()
                .count();
        if (sourceViews > 1) {
            throw reject("多事实视图查询缺少可验证的 source grain，禁止合并原始事实行");
        }
    }

    private static void validateConsumedBranchLineage(PlainSelect resultQuery,
                                                       List<Branch> branches,
                                                       QueryPlan plan) throws UnsafeSqlException {
        Set<String> approvedInputs = approvedSourceInputs(plan);
        for (Branch branch : branches) {
            if (branch.select() == resultQuery) continue;
            for (var item : branch.select().getSelectItems()) {
                Expression expression = unwrap(item.getExpression());
                if (!(expression instanceof Column column)) {
                    throw reject("CTE lineage 必须保持指标输入列不变，禁止中间计算: " + expression);
                }
                String input = column.getUnquotedColumnName().toLowerCase(Locale.ROOT);
                if (!approvedInputs.contains(input)) {
                    throw reject("CTE lineage 引用了计划之外的输入列: " + input);
                }
                if (item.getAlias() != null
                        && !item.getAlias().getUnquotedName().equalsIgnoreCase(input)) {
                    throw reject("CTE lineage 禁止重命名指标输入列: " + input);
                }
            }
        }
    }

    private static Set<String> approvedSourceInputs(QueryPlan plan) throws UnsafeSqlException {
        Set<String> columns = new LinkedHashSet<>(allowedDimensions(plan));
        for (MetricDefinition metric : plan.metrics()) {
            columns.add(metric.timeColumn().toLowerCase(Locale.ROOT));
            collectColumns(parseExpression(metric.expression()), columns);
            if (metric.condition() != null) {
                try {
                    collectColumns(CCJSqlParserUtil.parseCondExpression(metric.condition()), columns);
                } catch (Exception exception) {
                    throw reject("指标目录包含无法解析的固定条件");
                }
            }
        }
        return Set.copyOf(columns);
    }

    private static void collectColumns(Expression expression, Set<String> columns) {
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                columns.add(column.getUnquotedColumnName().toLowerCase(Locale.ROOT));
                return super.visit(column, context);
            }
        });
    }

    private static Statement parse(String sql) throws UnsafeSqlException {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception exception) {
            throw reject("已校验 SQL 无法重新解析查询计划");
        }
    }

    private static PlainSelect resultQuery(Select select) throws UnsafeSqlException {
        if (select instanceof PlainSelect plain) return plain;
        if (select instanceof ParenthesedSelect parenthesed) return resultQuery(parenthesed.getSelect());
        throw reject("查询计划只允许单一结果 SELECT");
    }

    private static List<Branch> consumedBranches(Select root, PlainSelect resultQuery)
            throws UnsafeSqlException {
        Map<String, WithItem<?>> ctes = new HashMap<>();
        if (root.getWithItemsList() != null) {
            for (WithItem<?> item : root.getWithItemsList()) {
                ctes.put(normalizeIdentifier(item.getAliasName()), item);
            }
        }
        List<Branch> branches = new ArrayList<>();
        Deque<PendingBranch> pending = new ArrayDeque<>();
        pending.add(new PendingBranch(resultQuery, List.of()));
        Set<String> usedCtes = new HashSet<>();
        Set<Select> expanded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!pending.isEmpty()) {
            PendingBranch current = pending.removeFirst();
            PlainSelect plain = current.select() instanceof ParenthesedSelect parenthesed
                    ? resultQuery(parenthesed.getSelect()) : (PlainSelect) current.select();
            if (!expanded.add(current.select())) continue;
            List<Expression> terms = new ArrayList<>(current.inheritedTerms());
            flattenAnd(plain.getWhere(), terms);
            branches.add(new Branch(plain, List.copyOf(terms), sourceTables(plain)));
            enqueue(plain.getFromItem(), terms, ctes, usedCtes, pending);
            if (plain.getJoins() != null) {
                for (var join : plain.getJoins()) {
                    enqueue(join.getRightItem(), terms, ctes, usedCtes, pending);
                }
            }
        }
        if (!usedCtes.containsAll(ctes.keySet())) {
            throw reject("未参与结果查询的 CTE 被拒绝，不能用于拼接指标约束");
        }
        return List.copyOf(branches);
    }

    private static void enqueue(Object fromItem, List<Expression> inheritedTerms,
                                Map<String, WithItem<?>> ctes, Set<String> usedCtes,
                                Deque<PendingBranch> pending) {
        if (fromItem instanceof Table table) {
            String name = normalizeIdentifier(table.getFullyQualifiedName());
            WithItem<?> cte = ctes.get(name);
            if (cte != null && usedCtes.add(name)) {
                pending.addLast(new PendingBranch(cte.getSelect(), inheritedTerms));
            }
        } else if (fromItem instanceof ParenthesedSelect nested) {
            pending.addLast(new PendingBranch(nested, inheritedTerms));
        }
    }

    private static Set<String> sourceTables(PlainSelect plain) {
        Set<String> tables = new LinkedHashSet<>();
        addSourceTable(plain.getFromItem(), tables);
        if (plain.getJoins() != null) {
            for (var join : plain.getJoins()) addSourceTable(join.getRightItem(), tables);
        }
        return Set.copyOf(tables);
    }

    private static void addSourceTable(Object item, Set<String> tables) {
        if (item instanceof Table table) tables.add(normalizeIdentifier(table.getFullyQualifiedName()));
    }

    private record PendingBranch(Select select, List<Expression> inheritedTerms) { }

    private record Branch(PlainSelect select, List<Expression> terms, Set<String> tables) { }

    private static void validateProjection(PlainSelect resultQuery, QueryPlan plan)
            throws UnsafeSqlException {
        Set<String> metricExpressions = new LinkedHashSet<>();
        for (MetricDefinition metric : plan.metrics()) {
            try {
                metricExpressions.add(canonicalProjection(CCJSqlParserUtil.parseExpression(metric.expression())));
            } catch (Exception exception) {
                throw reject("指标目录包含无法解析的聚合表达式 " + metric.expression());
            }
        }
        Set<String> projected = new LinkedHashSet<>();
        List<String> invalidProjections = new ArrayList<>();
        for (var item : resultQuery.getSelectItems()) {
            Expression expression = item.getExpression();
            String canonical = canonicalProjection(expression);
            projected.add(canonical);
            if (metricExpressions.contains(canonical)) continue;
            if (expression instanceof Column column) {
                if (!allowedDimensions(plan).contains(column.getUnquotedColumnName().toLowerCase(Locale.ROOT))) {
                    throw reject("查询选择了未获指标目录批准的维度 " + column.getUnquotedColumnName());
                }
                continue;
            }
            invalidProjections.add(expression.toString());
        }
        for (MetricDefinition metric : plan.metrics()) {
            String expected = canonicalProjection(parseExpression(metric.expression()));
            if (!projected.contains(expected)) {
                throw reject("指标 " + metric.name() + " 必须投影目录表达式 " + metric.expression());
            }
        }
        if (!invalidProjections.isEmpty()) {
            throw reject("查询包含未获指标目录批准的投影 " + invalidProjections);
        }
        if (resultQuery.getGroupBy() != null) {
            var groupBy = resultQuery.getGroupBy().getGroupByExpressionList();
            for (int i = 0; i < groupBy.size(); i++) {
                Expression expression = (Expression) groupBy.get(i);
                if (!(expression instanceof Column column)
                        || !allowedDimensions(plan).contains(column.getUnquotedColumnName().toLowerCase(Locale.ROOT))) {
                    throw reject("GROUP BY 使用了未获指标目录批准的维度 " + expression);
                }
            }
        }
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
        return terms.stream().anyMatch(term ->
                comparison(term, GreaterThanEquals.class, timeColumn, "fromTs")
                        || reversedComparison(term, net.sf.jsqlparser.expression.operators.relational.MinorThanEquals.class,
                                "fromTs", timeColumn));
    }

    private static boolean hasUpperBound(List<Expression> terms, String timeColumn) {
        return terms.stream().anyMatch(term ->
                comparison(term, MinorThan.class, timeColumn, "toTs")
                        || reversedComparison(term, GreaterThan.class, "toTs", timeColumn));
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

    private static String normalizeIdentifier(String name) {
        return name == null ? "" : name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static UnsafeSqlException reject(String message) {
        return new UnsafeSqlException("SQL_POLICY_REJECTED", message);
    }
}
