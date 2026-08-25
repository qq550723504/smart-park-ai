package com.example.smartpark.analytics.sql;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Rejects queries whose EXPLAIN (FORMAT JSON) estimated cost exceeds the
 * configured threshold before any data is touched. The EXPLAIN statement is
 * executed with exactly the same named-parameter bindings as the later real
 * execution so PostgreSQL never sees an unbound placeholder.
 */
public class QueryCostGuard {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final double maxCost;

    public QueryCostGuard(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, 1_000_000.0, null);
    }

    public QueryCostGuard(NamedParameterJdbcTemplate jdbcTemplate, double maxCost) {
        this(jdbcTemplate, maxCost, null);
    }

    /**
     * The EXPLAIN runs on the unrestricted analytics connection; without a
     * database-side timeout it could outlive the analysis timeout (thread
     * interruption does not issue JDBC Statement.cancel). Applying the same
     * statement timeout as the real query bounds it at the database.
     */
    public QueryCostGuard(NamedParameterJdbcTemplate jdbcTemplate, double maxCost,
                          java.time.Duration statementTimeout) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxCost = maxCost;
        if (statementTimeout != null) {
            jdbcTemplate.getJdbcTemplate().setQueryTimeout(Math.max(1, (int) statementTimeout.toSeconds()));
        }
    }

    public EstimatedPlan estimatedCost(String sql, Map<String, Object> parameters, double thresholdOverride)
            throws UnsafeSqlException {
        String explain = "EXPLAIN (FORMAT JSON) " + sql;
        List<String> planRows = jdbcTemplate.queryForList(explain,
                parameters == null ? new MapSqlParameterSource() : new MapSqlParameterSource(parameters),
                String.class);
        if (planRows.size() != 1) {
            throw new UnsafeSqlException("QUERY_COST_UNKNOWN", "无法获得查询计划，已拒绝执行");
        }
        String planJson = planRows.get(0);
        double cost = extractTotalCost(planJson);
        if (cost > thresholdOverride) {
            throw new UnsafeSqlException("QUERY_COST_EXCEEDED", "查询估算成本超过阈值，已拒绝执行");
        }
        return new EstimatedPlan(cost);
    }

    public EstimatedPlan estimatedCost(String sql, Map<String, Object> parameters) throws UnsafeSqlException {
        return estimatedCost(sql, parameters, maxCost);
    }

    /**
     * Extracts "Total Cost" from the JSON plan text without pulling in a full
     * JSON tree: the marker line is a stable PostgreSQL serialization detail.
     */
    static double extractTotalCost(String planJson) throws UnsafeSqlException {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"Total Cost\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(planJson);
        if (!matcher.find()) {
            throw new UnsafeSqlException("QUERY_COST_UNKNOWN", "查询计划缺少成本字段");
        }
        return Double.parseDouble(matcher.group(1));
    }

    public record EstimatedPlan(double planCost) {}
}
