package com.example.smartpark.analytics.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.smartpark.analytics.model.ValidatedSql;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @ParameterizedTest
    @ValueSource(strings = {
            // SELECT INTO materializes data outside the view boundary
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
}
