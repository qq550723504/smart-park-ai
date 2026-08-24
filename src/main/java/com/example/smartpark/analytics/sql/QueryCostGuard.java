package com.example.smartpark.analytics.sql;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Rejects queries whose EXPLAIN (FORMAT JSON) estimated cost exceeds the
 * configured threshold before any data is touched.
 */
public class QueryCostGuard {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final double maxCost;

    public QueryCostGuard(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, 1_000_000.0);
    }

    public QueryCostGuard(NamedParameterJdbcTemplate jdbcTemplate, double maxCost) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxCost = maxCost;
    }

    public EstimatedPlan estimatedCost(String sql, double thresholdOverride) throws UnsafeSqlException {
        String explain = "EXPLAIN (FORMAT JSON) " + sql;
        List<String> planRows = jdbcTemplate.getJdbcTemplate().queryForList(explain, String.class);
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

    public EstimatedPlan estimatedCost(String sql) throws UnsafeSqlException {
        return estimatedCost(sql, maxCost);
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
