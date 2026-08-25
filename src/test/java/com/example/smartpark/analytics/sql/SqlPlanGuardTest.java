package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.ValidatedSql;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlPlanGuardTest {

    private final MetricCatalog catalog = new MetricCatalog();

    @Test
    void acceptsPlannedSourceAndDirectTimeBounds() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh");
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE :fromTs <= hour_ts AND :toTs > hour_ts
                GROUP BY building_id LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTimeParametersUsedOnlyInTautologies() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh");
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE :fromTs = :fromTs AND :toTs = :toTs
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("hour_ts");
    }

    @Test
    void rejectsPlannedSourceMentionedOnlyAsLiteral() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh");
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT 'analytics.v_energy_hourly' FROM analytics.v_alert_fact
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("analytics.v_energy_hourly");
    }

    @Test
    void rejectsFixedMetricConditionMentionedOnlyAsStringLiteral() throws UnsafeSqlException {
        QueryPlan plan = plan("high_risk_alert_count");
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT COUNT(*) FROM analytics.v_alert_fact
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                  AND 'risk_level=HIGH' = 'risk_level=HIGH' LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("固定条件");
    }

    @Test
    void acceptsStructurallyEquivalentFixedMetricCondition() throws UnsafeSqlException {
        QueryPlan plan = plan("high_risk_alert_count");
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT COUNT(*) FROM analytics.v_alert_fact
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                  AND risk_level = 'HIGH' LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    private QueryPlan plan(String metricName) {
        MetricDefinition metric = catalog.findByName(metricName).orElseThrow();
        return new QueryPlan("test", List.of(metric), List.copyOf(metric.allowedDimensions()), Map.of(),
                new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")), 100);
    }
}
