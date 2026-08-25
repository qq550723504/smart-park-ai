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
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE :fromTs <= hour_ts AND :toTs > hour_ts
                GROUP BY building_id LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTimeParametersUsedOnlyInTautologies() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
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
        QueryPlan plan = plan("energy_kwh", List.of());
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT 'analytics.v_energy_hourly' FROM analytics.v_alert_fact
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("analytics.v_energy_hourly");
    }

    @Test
    void rejectsFixedMetricConditionMentionedOnlyAsStringLiteral() throws UnsafeSqlException {
        QueryPlan plan = plan("high_risk_alert_count", List.of());
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
        QueryPlan plan = plan("high_risk_alert_count", List.of());
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT COUNT(*) FROM analytics.v_alert_fact
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                  AND risk_level = 'HIGH' LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    @Test
    void acceptsNightConditionWithQualifiedTimeColumn() throws UnsafeSqlException {
        QueryPlan plan = plan("night_energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT e.building_id, SUM(e.kwh) FROM analytics.v_energy_hourly e
                WHERE e.hour_ts >= :fromTs AND e.hour_ts < :toTs
                  AND (EXTRACT(HOUR FROM e.hour_ts AT TIME ZONE 'Asia/Shanghai') >= 22
                       OR EXTRACT(HOUR FROM e.hour_ts AT TIME ZONE 'Asia/Shanghai') < 6)
                GROUP BY e.building_id LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsProjectionThatDoesNotImplementCatalogMetricExpression() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of());
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT COUNT(*) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("指标投影");
    }

    @Test
    void rejectsProjectionDimensionOutsideMetricCatalog() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT customer_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY customer_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("customer_id");
    }

    @Test
    void rejectsCatalogDimensionThatWasNotRequestedByTheUser() throws UnsafeSqlException {
        MetricDefinition metric = catalog.findByName("energy_kwh").orElseThrow();
        QueryPlan totalPlan = new QueryPlan("total energy", List.of(metric), List.of(), Map.of(),
                new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")), 100);
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, totalPlan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("building_id");
    }

    @Test
    void rejectsTimeBoundsHiddenInUnusedCte() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of());
        ValidatedSql sql = SqlAstGuard.validate("""
                WITH bounded AS (
                    SELECT kwh FROM analytics.v_energy_hourly
                    WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                )
                SELECT COUNT(*) FROM analytics.v_energy_hourly LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("CTE");
    }

    @Test
    void validatesPredicatesInsideTheCteThatProducesTheResult() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                WITH recent AS (
                    SELECT building_id, kwh FROM analytics.v_energy_hourly
                    WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                )
                SELECT building_id, SUM(kwh) FROM recent
                GROUP BY building_id LIMIT 100""");

        assertThatCode(() -> SqlPlanGuard.validate(sql, plan)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRawJoinThatCombinesMetricsFromDifferentFactViews() throws UnsafeSqlException {
        MetricDefinition energy = catalog.findByName("energy_kwh").orElseThrow();
        MetricDefinition alerts = catalog.findByName("alert_count").orElseThrow();
        QueryPlan plan = new QueryPlan("energy and alerts", List.of(energy, alerts), List.of("building_id"),
                Map.of(), new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")), 100);
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT e.building_id, SUM(e.kwh), COUNT(*)
                FROM analytics.v_energy_hourly e
                JOIN analytics.v_alert_fact a ON a.building_id = e.building_id
                WHERE e.hour_ts >= :fromTs AND e.hour_ts < :toTs
                  AND a.occurred_at >= :fromTs AND a.occurred_at < :toTs
                GROUP BY e.building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("source grain");
    }

    @Test
    void rejectsRepeatedOccurrenceOfThePlannedFactView() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT e.building_id, SUM(e.kwh)
                FROM analytics.v_energy_hourly e
                JOIN analytics.v_energy_hourly duplicate
                  ON duplicate.building_id = e.building_id
                WHERE e.hour_ts >= :fromTs AND e.hour_ts < :toTs
                GROUP BY e.building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("JOIN");
    }

    @Test
    void rejectsRepeatedCteReferenceThatMultipliesTheFactRows() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                WITH recent AS (
                    SELECT building_id, hour_ts, kwh FROM analytics.v_energy_hourly
                )
                SELECT a.building_id, SUM(a.kwh)
                FROM recent a JOIN recent b ON a.building_id = b.building_id
                WHERE a.hour_ts >= :fromTs AND a.hour_ts < :toTs
                GROUP BY a.building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("JOIN");
    }

    @Test
    void rejectsHavingPredicateAbsentFromThePlan() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id HAVING SUM(kwh) > 100 LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("HAVING");
    }

    @Test
    void rejectsDuplicateMetricProjection() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh), SUM(kwh) AS energy_kwh
                FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("投影");
    }

    @Test
    void rejectsProjectionAliasesThatChangePlanOutputIdentity() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id AS wrong_name, SUM(kwh) AS arbitrary
                FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("别名");
    }

    @Test
    void rejectsMetricAliasThatIsNotThePlanMetricName() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) AS arbitrary
                FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("别名");
    }

    @Test
    void rejectsMultiViewPlanWhenSeparateCtesStillExposeRawFactRows() throws UnsafeSqlException {
        MetricDefinition energy = catalog.findByName("energy_kwh").orElseThrow();
        MetricDefinition alerts = catalog.findByName("alert_count").orElseThrow();
        QueryPlan plan = new QueryPlan("energy and alerts", List.of(energy, alerts), List.of("building_id"),
                Map.of(), new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")), 100);
        ValidatedSql sql = SqlAstGuard.validate("""
                WITH energy AS (
                    SELECT building_id, kwh FROM analytics.v_energy_hourly
                    WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                ), alerts AS (
                    SELECT building_id FROM analytics.v_alert_fact
                    WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                )
                SELECT e.building_id, SUM(e.kwh), COUNT(*)
                FROM energy e JOIN alerts a ON a.building_id = e.building_id
                GROUP BY e.building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("source grain");
    }

    @Test
    void rejectsSameViewMetricsThatRequireDifferentPredicateScopes() throws UnsafeSqlException {
        MetricDefinition allAlerts = catalog.findByName("alert_count").orElseThrow();
        MetricDefinition highRiskAlerts = catalog.findByName("high_risk_alert_count").orElseThrow();
        QueryPlan plan = new QueryPlan("all and high risk alerts", List.of(allAlerts, highRiskAlerts),
                List.of("building_id"), Map.of(), new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")), 100);
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, COUNT(*) AS all_alerts, COUNT(*) AS high_risk_alerts
                FROM analytics.v_alert_fact
                WHERE occurred_at >= :fromTs AND occurred_at < :toTs
                  AND risk_level = 'HIGH'
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("metric-specific predicate");
    }

    @Test
    void rejectsConsumedCteThatTransformsMetricInputBeforeAggregation() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                WITH recent AS (
                    SELECT building_id, kwh * 100 AS kwh FROM analytics.v_energy_hourly
                    WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                )
                SELECT building_id, SUM(kwh) FROM recent
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("lineage");
    }

    @Test
    void rejectsProjectedDimensionsMissingFromGroupBy() throws UnsafeSqlException {
        // A projected non-aggregate dimension without a matching GROUP BY makes
        // PostgreSQL reject the query at EXPLAIN time as ANALYSIS_ABORTED;
        // the guard must catch it as a repairable rejection instead.
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("GROUP BY");
    }

    @Test
    void rejectsMissingRequestedDimension() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("building_id");
    }

    @Test
    void rejectsPredicateAbsentFromThePlan() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of());
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                  AND building_id = 'B1' LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("计划之外");
    }

    @Test
    void rejectsExtraPredicateOnAProjectedDimension() throws UnsafeSqlException {
        QueryPlan plan = plan("energy_kwh", List.of("building_id"));
        ValidatedSql sql = SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                  AND building_id = 'B1'
                GROUP BY building_id LIMIT 100""");

        assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("计划之外");
    }

    private QueryPlan plan(String metricName, List<String> dimensions) {
        MetricDefinition metric = catalog.findByName(metricName).orElseThrow();
        return new QueryPlan("test", List.of(metric), dimensions, Map.of(),
                new QueryPlan.TimeRange(
                        Instant.parse("2026-08-17T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")), 100);
    }
}
