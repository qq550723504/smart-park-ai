package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.model.ValidatedSql;
import net.sf.jsqlparser.expression.DateValue;
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
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

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

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "analytics.v_energy_hourly",
            "analytics.v_alert_fact",
            "analytics.v_device_snapshot",
            "analytics.v_parking_daily");

    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "round", "coalesce", "nullif",
            "extract", "date_trunc", "cast", "greatest", "least");

    private static final int MAX_ROWS = 500;

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
        Set<String> cteAliases = new LinkedHashSet<>();
        for (WithItem<?> withItem : withItems(select)) {
            if (withItem.getAliasName() != null) {
                cteAliases.add(normalizeIdentifier(withItem.getAliasName()));
            }
        }
        Set<String> tables = new LinkedHashSet<>();
        TablesNamesFinder<Void> finder = new TablesNamesFinder<>();
        for (String name : finder.getTables(statement)) {
            tables.add(normalizeIdentifier(name));
        }
        tables.removeAll(cteAliases);
        for (String table : tables) {
            if (!ALLOWED_TABLES.contains(table)) {
                throw reject("引用了白名单之外的表或视图");
            }
        }

        // Function allow-list, bound-parameter-only time values.
        ExpressionPolicy policy = new ExpressionPolicy();
        for (PlainSelect plain : plainSelects) {
            walk(plain.getWhere(), policy);
            for (var item : plain.getSelectItems()) {
                walk(item.getExpression(), policy);
            }
            if (plain.getOrderByElements() != null) {
                for (net.sf.jsqlparser.statement.select.OrderByElement element : plain.getOrderByElements()) {
                    walk(element.getExpression(), policy);
                }
            }
        }

        // Hard row bound: explicit LIMIT within the contract.
        Limit limit = plainSelects.get(0).getLimit();
        if (limit == null || limit.getRowCount() == null || !(limit.getRowCount() instanceof LongValue rowCount)
                || rowCount.getValue() < 1 || rowCount.getValue() > MAX_ROWS) {
            throw reject("必须携带 1..500 的 LIMIT 子句");
        }

        return new ValidatedSql(sql.strip(), new ArrayList<>(policy.namedParameters), (int) rowCount.getValue());
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
        if (node instanceof PlainSelect plain) {
            into.add(plain);
        } else if (node instanceof WithItem<?> withItem) {
            collect(withItem.getParenthesedStatement(), into, depth + 1);
        } else if (node instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesed) {
            collect(parenthesed.getSelect(), into, depth + 1);
        } else {
            throw reject("不支持的查询结构");
        }
    }

    private static String normalizeIdentifier(String name) {
        return name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
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
