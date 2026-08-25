package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.model.ValidatedSql;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AST-based SQL safety gate. Every decision is made on the parsed syntax tree
 * (JSqlParser) — never on regular expressions. Any parse failure or unknown
 * construct fails closed.
 */
public final class SqlAstGuard {

    private static final Set<SqlRelationName> ALLOWED_TABLES = Set.of(
            SqlRelationName.parseCatalogName("analytics.v_energy_hourly"),
            SqlRelationName.parseCatalogName("analytics.v_alert_fact"),
            SqlRelationName.parseCatalogName("analytics.v_device_snapshot"),
            SqlRelationName.parseCatalogName("analytics.v_parking_daily"));

    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "round", "coalesce", "nullif",
            "extract", "date_trunc", "cast", "greatest", "least");

    /** Upper row bound shared with the analytics configuration contract. */
    public static final int MAX_ROWS = 500;

    private static final Set<String> TEMPORAL_COLUMNS = Set.of(
            "hour_ts", "occurred_at", "snapshot_at", "stat_date");

    private SqlAstGuard() {
    }

    public static ValidatedSql validate(String sql) throws UnsafeSqlException {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeSqlException("SQL_POLICY_REJECTED", "SQL 为空，已拒绝执行");
        }
        // Comments are never needed for analysis queries and are a classic smuggling channel.
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/")) {
            throw reject("SQL 包含注释，已拒绝执行");
        }
        // One statement only; even a trailing semicolon is refused to keep parsing unambiguous.
        if (sql.contains(";")) {
            throw reject("检测到多语句边界，已拒绝执行");
        }

        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception exception) {
            throw new UnsafeSqlException("SQL_UNPARSEABLE", "SQL 无法解析为受支持的单条查询");
        }
        if (!(statement instanceof Select select)) {
            throw reject("只允许 SELECT 查询");
        }

        for (WithItem<?> withItem : withItems(select)) {
            if (withItem.isRecursive()) {
                throw reject("递归 CTE 被拒绝");
            }
        }
        if (select instanceof SetOperationList) {
            throw reject("集合运算被拒绝，仅允许单条查询");
        }

        List<PlainSelect> plainSelects = plainSelects(select);
        for (PlainSelect plain : plainSelects) {
            if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
                throw reject("SELECT INTO 被拒绝");
            }
        }
        // Table whitelist: every referenced relation must be an analytics view.
        validateRelations(select, plainSelects);

        // Function allow-list, bound-parameter-only time values. Every
        // clause that carries expressions (select items, WHERE, JOIN ON,
        // GROUP BY, HAVING, ORDER BY) is walked by the allow-list visitor —
        // an unchecked clause would let a forbidden function or literal hide
        // from the fail-closed gate.
        ExpressionPolicy policy = new ExpressionPolicy();
        for (PlainSelect plain : plainSelects) {
            walk(plain.getWhere(), policy);
            for (var item : plain.getSelectItems()) {
                walk(item.getExpression(), policy);
            }
            if (plain.getJoins() != null) {
                for (var join : plain.getJoins()) {
                    if (join.getOnExpressions() != null) {
                        for (Expression onExpression : join.getOnExpressions()) {
                            walk(onExpression, policy);
                        }
                    }
                }
            }
            if (plain.getGroupBy() != null) {
                var groupByList = plain.getGroupBy().getGroupByExpressionList();
                for (int i = 0; i < groupByList.size(); i++) {
                    walk((Expression) groupByList.get(i), policy);
                }
            }
            walk(plain.getHaving(), policy);
            if (plain.getOrderByElements() != null) {
                for (net.sf.jsqlparser.statement.select.OrderByElement element : plain.getOrderByElements()) {
                    walk(element.getExpression(), policy);
                }
            }
        }

        // Hard row bound: explicit LIMIT within the contract.
        Limit limit = resultLimit(select);
        if (limit == null || limit.getRowCount() == null || !(limit.getRowCount() instanceof LongValue rowCount)
                || rowCount.getValue() < 1 || rowCount.getValue() > MAX_ROWS) {
            throw reject("必须携带 1..500 的 LIMIT 子句");
        }

        return new ValidatedSql(sql.strip(), new ArrayList<>(policy.namedParameters), (int) rowCount.getValue());
    }

    private static Limit resultLimit(Select select) throws UnsafeSqlException {
        if (select instanceof PlainSelect plain) return plain.getLimit();
        if (select instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesed) {
            return resultLimit(parenthesed.getSelect());
        }
        throw reject("不支持的查询结构");
    }

    private static void walk(Expression expression, ExpressionPolicy policy) throws UnsafeSqlException {
        try {
            if (expression != null) {
                expression.accept(policy);
            }
        } catch (IllegalStateException violation) {
            throw reject(violation.getMessage());
        }
    }

    private static List<WithItem<?>> withItems(Select select) {
        return select.getWithItemsList() == null ? List.of() : select.getWithItemsList();
    }

    private static List<PlainSelect> plainSelects(Select select) throws UnsafeSqlException {
        List<PlainSelect> result = new ArrayList<>();
        collect(select, result, 0);
        if (result.isEmpty()) {
            throw reject("不支持的查询结构");
        }
        return result;
    }

    private static void collect(Object node, List<PlainSelect> into, int depth) throws UnsafeSqlException {
        if (depth > 8) {
            throw reject("查询嵌套过深，已拒绝");
        }
        if (node instanceof WithItem<?> withItem) {
            collect(withItem.getParenthesedStatement(), into, depth + 1);
        } else if (node instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesed) {
            collect(parenthesed.getSelect(), into, depth + 1);
        } else if (node instanceof Select select) {
            // A CTE belongs to the SELECT that declares it. Walking only the
            // outer PlainSelect would let a forbidden function hide in an
            // unused or nested CTE body.
            for (WithItem<?> withItem : withItems(select)) {
                collect(withItem, into, depth + 1);
            }
            if (select instanceof PlainSelect plain) {
                into.add(plain);
                if (plain.getFromItem() instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect nested) {
                    collect(nested, into, depth + 1);
                }
                if (plain.getJoins() != null) {
                    for (var join : plain.getJoins()) {
                        if (join.getRightItem() instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect nested) {
                            collect(nested, into, depth + 1);
                        }
                    }
                }
            } else {
                throw reject("不支持的查询结构");
            }
        } else {
            throw reject("不支持的查询结构");
        }
    }


    private static void validateRelations(Select select, List<PlainSelect> plainSelects)
            throws UnsafeSqlException {
        Set<String> cteAliases = new LinkedHashSet<>();
        try {
            for (WithItem<?> withItem : withItems(select)) {
                if (withItem.getAliasName() != null) {
                    cteAliases.add(SqlRelationName.component(withItem.getAliasName()));
                }
            }
            for (PlainSelect plain : plainSelects) {
                validateRelation(plain.getFromItem(), cteAliases);
                if (plain.getJoins() != null) {
                    for (var join : plain.getJoins()) {
                        validateRelation(join.getRightItem(), cteAliases);
                    }
                }
            }
        } catch (IllegalArgumentException malformedIdentifier) {
            throw reject("引用了不受支持的 PostgreSQL 标识符");
        }
    }

    private static void validateRelation(Object fromItem, Set<String> cteAliases)
            throws UnsafeSqlException {
        if (!(fromItem instanceof Table table)) {
            return;
        }
        SqlRelationName relation = SqlRelationName.from(table);
        if (!relation.isQualified() && cteAliases.contains(relation.relation())) {
            return;
        }
        if (!ALLOWED_TABLES.contains(relation)) {
            throw reject("引用了白名单之外的表或视图");
        }
    }

    private static UnsafeSqlException reject(String safeReason) {
        return new UnsafeSqlException("SQL_POLICY_REJECTED", safeReason);
    }

    /** Collects named parameters and rejects every disallowed expression form. */
    private static final class ExpressionPolicy extends ExpressionVisitorAdapter<Void> {
        private final Set<String> namedParameters = new LinkedHashSet<>();

        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName() == null ? "" : function.getName().toLowerCase(Locale.ROOT);
            if (!ALLOWED_FUNCTIONS.contains(name)) {
                throw new IllegalStateException("使用了白名单之外的函数");
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(JdbcNamedParameter parameter, S context) {
            namedParameters.add(parameter.getName());
            return super.visit(parameter, context);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.expression.StringValue value, S context) {
            // A quoted literal that itself parses as a calendar value is a time boundary;
            // boundaries must be bound parameters, never embedded constants.
            String raw = value.getValue();
            if (raw.matches("\\d{4}-\\d{1,2}(-\\d{1,2})?([ T].*)?")) {
                throw new IllegalStateException("时间边界必须是绑定参数，不允许字面量日期");
            }
            return super.visit(value, context);
        }

        @Override
        public <S> Void visit(CastExpression cast, S context) {
            if ((cast.isDate() || cast.isTime() || cast.isTimeStamp())
                    && unwrap(cast.getLeftExpression()) instanceof net.sf.jsqlparser.expression.StringValue) {
                throw new IllegalStateException("时间边界必须是绑定参数，不允许字符串转换为时间类型");
            }
            return super.visit(cast, context);
        }

        @Override
        public <S> Void visit(EqualsTo comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        @Override
        public <S> Void visit(NotEqualsTo comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        @Override
        public <S> Void visit(GreaterThan comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        @Override
        public <S> Void visit(GreaterThanEquals comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        @Override
        public <S> Void visit(MinorThan comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        @Override
        public <S> Void visit(MinorThanEquals comparison, S context) {
            rejectImplicitTemporalLiteral(comparison);
            return super.visit(comparison, context);
        }

        private static void rejectImplicitTemporalLiteral(BinaryExpression comparison) {
            Expression left = unwrap(comparison.getLeftExpression());
            Expression right = unwrap(comparison.getRightExpression());
            if ((isTemporalExpression(left) && isStringLiteral(right))
                    || (isTemporalExpression(right) && isStringLiteral(left))) {
                throw new IllegalStateException("时间边界必须是绑定参数，不允许字符串隐式转换为时间类型");
            }
        }

        private static boolean isTemporalExpression(Expression expression) {
            boolean[] temporal = {false};
            expression.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column column, S context) {
                    if (TEMPORAL_COLUMNS.contains(
                            column.getUnquotedColumnName().toLowerCase(Locale.ROOT))) {
                        temporal[0] = true;
                    }
                    return super.visit(column, context);
                }
            });
            return temporal[0];
        }

        private static boolean isStringLiteral(Expression expression) {
            if (expression instanceof net.sf.jsqlparser.expression.StringValue) return true;
            return expression instanceof CastExpression cast
                    && unwrap(cast.getLeftExpression()) instanceof net.sf.jsqlparser.expression.StringValue;
        }

        private static Expression unwrap(Expression expression) {
            Expression value = expression;
            while (value instanceof net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList<?> list
                    && list.size() == 1) {
                value = list.get(0);
            }
            return value;
        }

        @Override
        public <S> Void visit(JdbcParameter parameter, S context) {
            throw new IllegalStateException("位置占位符被拒绝，时间与数值边界必须使用命名参数");
        }

        @Override
        public <S> Void visit(DateValue value, S context) {
            throw new IllegalStateException("时间边界必须是绑定参数，不允许字面量日期");
        }

        @Override
        public <S> Void visit(TimeValue value, S context) {
            throw new IllegalStateException("时间边界必须是绑定参数，不允许字面量时间");
        }

        @Override
        public <S> Void visit(TimestampValue value, S context) {
            throw new IllegalStateException("时间边界必须是绑定参数，不允许字面量时间戳");
        }
    }
}
