package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.ValidatedSql;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
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
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

        Set<String> actualTables = actualTables(statement, select);
        List<Expression> conjuncts = new ArrayList<>();
        for (PlainSelect plain : plainSelects(select)) {
            flattenAnd(plain.getWhere(), conjuncts);
        }

        for (MetricDefinition metric : plan.metrics()) {
            if (!actualTables.contains(normalizeIdentifier(metric.sourceView()))) {
                throw reject("查询缺少计划要求的数据视图 " + metric.sourceView());
            }
            if (!hasLowerBound(conjuncts, metric.timeColumn())) {
                throw reject("时间列 " + metric.timeColumn() + " 缺少 :fromTs 包含下界");
            }
            if (!hasUpperBound(conjuncts, metric.timeColumn())) {
                throw reject("时间列 " + metric.timeColumn() + " 缺少 :toTs 排除上界");
            }
            if (metric.condition() != null && !hasFixedCondition(conjuncts, metric.condition())) {
                throw reject("查询缺少指标的固定条件，请按口径过滤");
            }
        }
    }

    private static Statement parse(String sql) throws UnsafeSqlException {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception exception) {
            throw reject("已校验 SQL 无法重新解析查询计划");
        }
    }

    private static Set<String> actualTables(Statement statement, Select select) {
        Set<String> aliases = new LinkedHashSet<>();
        if (select.getWithItemsList() != null) {
            select.getWithItemsList().forEach(item -> aliases.add(normalizeIdentifier(item.getAliasName())));
        }
        Set<String> tables = new LinkedHashSet<>();
        new TablesNamesFinder<Void>().getTables(statement).stream()
                .map(SqlPlanGuard::normalizeIdentifier)
                .filter(name -> !aliases.contains(name))
                .forEach(tables::add);
        return tables;
    }

    private static List<PlainSelect> plainSelects(Select select) {
        List<PlainSelect> result = new ArrayList<>();
        collect(select, result);
        return result;
    }

    private static void collect(Select select, List<PlainSelect> result) {
        if (select.getWithItemsList() != null) {
            select.getWithItemsList().stream()
                    .map(item -> item.getSelect())
                    .filter(java.util.Objects::nonNull)
                    .forEach(item -> collect(item, result));
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            collect(parenthesed.getSelect(), result);
            return;
        }
        if (!(select instanceof PlainSelect plain)) {
            return;
        }
        result.add(plain);
        if (plain.getFromItem() instanceof ParenthesedSelect nested) {
            collect(nested, result);
        }
        if (plain.getJoins() != null) {
            plain.getJoins().stream()
                    .map(join -> join.getRightItem())
                    .filter(ParenthesedSelect.class::isInstance)
                    .map(ParenthesedSelect.class::cast)
                    .forEach(nested -> collect(nested, result));
        }
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
