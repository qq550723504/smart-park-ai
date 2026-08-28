package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.model.QueryPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanSqlRendererTest {

    private final MetricCatalog catalog = new MetricCatalog();

    @Test
    void rendersGroupedEnergyFromValidatedPlan() throws UnsafeSqlException {
        var plan = plan("energy_kwh", List.of("building_id"), Map.of());

        String sql = new QueryPlanSqlRenderer().render(plan);

        assertThat(sql).isEqualTo("SELECT building_id, SUM(kwh) AS energy_kwh "
                + "FROM analytics.v_energy_hourly "
                + "WHERE hour_ts >= :fromTs AND hour_ts < :toTs "
                + "GROUP BY building_id LIMIT 200");
        var validated = SqlAstGuard.validate(sql);
        SqlPlanGuard.validate(validated, plan);
        assertThat(validated.namedParameters())
                .containsExactly("fromTs", "toTs");
    }

    @Test
    void rendersMetricConditionAndBoundEntityFilterWithoutModelSql() throws UnsafeSqlException {
        var plan = plan("night_energy_kwh", List.of(), Map.of("building_id", "B1"), "B1楼宇夜间能耗");

        String sql = new QueryPlanSqlRenderer().render(plan);

        assertThat(sql).contains("EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai')");
        assertThat(sql).contains("building_id = :filter_building_id");
        assertThat(sql).doesNotContain("B1");
        var validated = SqlAstGuard.validate(sql);
        SqlPlanGuard.validate(validated, plan);
        assertThat(validated.namedParameters())
                .containsExactly("fromTs", "toTs", "filter_building_id");
    }

    private QueryPlan plan(String metricName, List<String> dimensions, Map<String, String> filters) {
        return plan(metricName, dimensions, filters, "过去5天能耗");
    }

    private QueryPlan plan(String metricName, List<String> dimensions, Map<String, String> filters,
                           String question) {
        var metric = catalog.findByName(metricName).orElseThrow();
        return new QueryPlan(
                question,
                List.of(metric),
                dimensions,
                filters,
                new QueryPlan.TimeRange(
                        Instant.parse("2026-08-23T00:00:00Z"),
                        Instant.parse("2026-08-28T00:00:00Z")),
                200);
    }
}
