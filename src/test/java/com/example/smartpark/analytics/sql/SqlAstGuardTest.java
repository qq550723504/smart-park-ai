package com.example.smartpark.analytics.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.smartpark.analytics.model.ValidatedSql;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlAstGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // simple aggregate over a whitelisted view with bound time parameters
            """
            SELECT building_id, SUM(kwh) AS kwh FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromHour AND hour_ts < :toHour GROUP BY building_id ORDER BY building_id LIMIT 200""",
            // read-only CTE
            """
            WITH recent AS (SELECT alert_id FROM analytics.v_alert_fact WHERE risk_level = 'HIGH')
            SELECT COUNT(*) AS c FROM recent LIMIT 10""",
            // join between two whitelisted views
            """
            SELECT d.building_id, COUNT(*) AS offline
            FROM analytics.v_device_snapshot d JOIN analytics.v_alert_fact a ON a.device_id = d.device_id
            WHERE d.status = 'OFFLINE' GROUP BY d.building_id ORDER BY offline DESC LIMIT 50""",
    })
    void allowsWhitelistedReadOnlyQueries(String sql) throws UnsafeSqlException {
        ValidatedSql validated = SqlAstGuard.validate(sql);
        assertThat(validated.maxRows()).isBetween(1, 500);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO analytics.v_alert_fact VALUES ('x','b','d','c','LOW',now(),'OPEN') LIMIT 1",
            "UPDATE analytics.v_parking_daily SET entries = 0 LIMIT 1",
            "DELETE FROM analytics.v_alert_fact LIMIT 1",
            "DROP TABLE analytics.v_alert_fact",
            "CREATE TABLE analytics.evil(id int)",
            "ALTER TABLE analytics.v_alert_fact RENAME TO x",
            "TRUNCATE TABLE analytics.v_alert_fact",
            "GRANT ALL ON analytics.v_alert_fact TO PUBLIC",
    })
    void rejectsDmlDdlAndPrivilegeStatements(String sql) {
        // Fail closed: every non-SELECT statement is refused regardless of whether
        // the parser models it natively or refuses to parse it.
        assertThatThrownBy(() -> SqlAstGuard.validate(sql))
                .isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void rejectsTrailingOffsetAtTheRepairableValidationStage() {
        // jsqlparser drops a trailing OFFSET from its AST, so the structural
        // gates cannot see it. The guard must reject the raw shape here — at
        // the stage that still routes to the model's SQL-repair attempt —
        // instead of failing terminally inside the executor.
        assertThatThrownBy(() -> SqlAstGuard.validate(
                "SELECT building_id, kwh FROM analytics.v_energy_hourly LIMIT 100 OFFSET 0"))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("OFFSET");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT alert_id INTO analytics.evil FROM analytics.v_alert_fact LIMIT 5",
            // multi statement smuggling
            "SELECT 1; DROP TABLE analytics.v_alert_fact",
            // comment concatenation
            "SELECT /* hidden */ building_id FROM analytics.v_energy_hourly LIMIT 5",
            "SELECT building_id -- comment\n FROM analytics.v_energy_hourly LIMIT 5",
            // objects outside the whitelist
            "SELECT table_name FROM information_schema.tables LIMIT 5",
            "SELECT rolname FROM pg_catalog.pg_roles LIMIT 5",
            "SELECT * FROM other_schema.v_something LIMIT 5",
            "SELECT version() LIMIT 1",
            // dangerous function
            "SELECT pg_sleep(10) FROM analytics.v_alert_fact LIMIT 1",
            // dangerous function hidden in a JOIN ON clause
            """
            SELECT d.building_id FROM analytics.v_device_snapshot d
            JOIN analytics.v_alert_fact a ON a.device_id = d.device_id AND pg_sleep(0) IS NOT NULL
            WHERE d.status = 'OFFLINE' LIMIT 10""",
            // dangerous function hidden in HAVING
            """
            SELECT building_id, COUNT(*) FROM analytics.v_energy_hourly
            GROUP BY building_id HAVING pg_sleep(0) IS NOT NULL LIMIT 10""",
            // dangerous function hidden in GROUP BY
            """
            SELECT building_id, COUNT(*) FROM analytics.v_energy_hourly
            GROUP BY building_id, (CASE WHEN pg_sleep(0) IS NULL THEN 1 ELSE 2 END) LIMIT 10""",
            // dangerous function hidden in an otherwise unused CTE
            """
            WITH sneaky AS (SELECT pg_sleep(0) FROM analytics.v_alert_fact)
            SELECT alert_id FROM analytics.v_alert_fact LIMIT 10""",
            // unbounded query
            "SELECT building_id FROM analytics.v_energy_hourly",
            // limit beyond contract
            "SELECT building_id FROM analytics.v_energy_hourly LIMIT 501",
            // literal time values instead of bound parameters
            "SELECT building_id FROM analytics.v_energy_hourly WHERE hour_ts > '2026-08-01' LIMIT 5",
            "SELECT building_id FROM analytics.v_energy_hourly WHERE hour_ts > 'August 1, 2026' LIMIT 5",
            "SELECT building_id FROM analytics.v_energy_hourly WHERE DATE_TRUNC('day', hour_ts) > 'August 1, 2026' LIMIT 5",
            "SELECT building_id FROM analytics.v_energy_hourly WHERE hour_ts > CAST('August 1, 2026' AS timestamp) LIMIT 5",
            "SELECT building_id FROM analytics.v_energy_hourly WHERE hour_ts > DATE '2026-08-01' LIMIT 5",
            // positional placeholder
            "SELECT building_id FROM analytics.v_energy_hourly WHERE kwh > ? LIMIT 5",
            // recursive CTE
            """
            WITH RECURSIVE r AS (SELECT alert_id FROM analytics.v_alert_fact
                                 UNION ALL SELECT alert_id + 1 FROM r)
            SELECT COUNT(*) FROM r LIMIT 5""",
    })
    void failsClosedOnPolicyViolations(String sql) {
        assertThatThrownBy(() -> SqlAstGuard.validate(sql))
                .isInstanceOf(UnsafeSqlException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SQL_POLICY_REJECTED")
                .satisfies(exception ->
                        assertThat(exception.getMessage()).doesNotContain("jdbc", "password", "secret"));
    }

    @Test
    void unparseableInputFailsClosedWithoutLeakingDetails() {
        assertThatThrownBy(() -> SqlAstGuard.validate("SELECT !!! FROM nowhere"))
                .isInstanceOf(UnsafeSqlException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SQL_UNPARSEABLE");
    }

    @Test
    void extractsNamedParametersInOrder() throws UnsafeSqlException {
        ValidatedSql validated = SqlAstGuard.validate(
                "SELECT building_id FROM analytics.v_energy_hourly WHERE hour_ts >= :fromHour AND hour_ts < :toHour LIMIT 10");
        assertThat(validated.namedParameters()).isEqualTo(List.of("fromHour", "toHour"));
    }

    @Test
    void rejectsSingleQuotedIdentifierThatOnlyLooksQualified() {
        assertThatThrownBy(() -> SqlAstGuard.validate("""
                SELECT SUM(kwh) FROM "analytics.v_energy_hourly"
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100"""))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void acceptsSeparatelyQuotedSchemaAndViewComponents() {
        assertThatCode(() -> SqlAstGuard.validate("""
                SELECT SUM(kwh) FROM "analytics"."v_energy_hourly"
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100"""))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTableValuedFunctionsInFromItems() {
        assertThatThrownBy(() -> SqlAstGuard.validate("""
                SELECT e.building_id, SUM(e.kwh)
                FROM analytics.v_energy_hourly e
                CROSS JOIN generate_series(1, 2) multiplier
                WHERE e.hour_ts >= :fromTs AND e.hour_ts < :toTs
                GROUP BY e.building_id LIMIT 100"""))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("FROM");
    }

    @Test
    void rejectsScalarSubqueriesHiddenInOrderByExpressions() {
        assertThatThrownBy(() -> SqlAstGuard.validate("""
                SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromTs AND hour_ts < :toTs
                GROUP BY building_id
                ORDER BY (SELECT COUNT(*) FROM pg_catalog.pg_roles r WHERE r.rolname < building_id)
                LIMIT 100"""))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("子查询");
    }
}
