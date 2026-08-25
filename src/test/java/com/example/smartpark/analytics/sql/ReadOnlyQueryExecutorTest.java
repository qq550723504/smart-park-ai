package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes real queries against a real PostgreSQL through the application's
 * read-only role: bounded time, bounded rows, bounded bytes, no writes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadOnlyQueryExecutorTest {

    private PostgreSQLContainer<?> postgres;
    private DataSource readOnlyDataSource;
    private ReadOnlyQueryExecutor executor;
    private NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeAll
    void startContainerAndMigrate() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("smartpark");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("analyticsRoPassword", "test-ro-pass"))
                .load()
                .migrate();

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        var dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername("smartpark_analytics_ro");
        dataSource.setPassword("test-ro-pass");
        readOnlyDataSource = dataSource;
        jdbcTemplate = new NamedParameterJdbcTemplate(readOnlyDataSource);
        executor = new ReadOnlyQueryExecutor(readOnlyDataSource,
                new ReadOnlyQueryExecutor.QueryLimits(java.time.Duration.ofSeconds(3), 500, 1024 * 1024, 1_000_000.0));
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private ValidatedSql validated(String sql) {
        return new ValidatedSql(sql, List.of(), 500);
    }

    @Test
    void executesWhitelistedQueriesWithBoundParameters() throws UnsafeSqlException {
        ValidatedSql sql = new ValidatedSql("""
                SELECT building_id, SUM(kwh) AS total FROM analytics.v_energy_hourly
                WHERE hour_ts >= :fromHour AND hour_ts < :toHour
                GROUP BY building_id ORDER BY building_id LIMIT 100""",
                List.of("fromHour", "toHour"), 100);

        TabularResult result = executor.execute(sql, Map.of(
                "fromHour", java.time.OffsetDateTime.parse("2026-08-20T00:00:00+08:00"),
                "toHour", java.time.OffsetDateTime.parse("2026-08-25T00:00:00+08:00")));

        assertThat(result.columnNames()).containsExactly("building_id", "total");
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void truncatesResultsAtTheConfiguredRowBound() throws UnsafeSqlException {
        // Constructed directly (bypassing the AST guard) to prove the executor's own cap.
        ValidatedSql sql = new ValidatedSql("SELECT building_id, kwh FROM analytics.v_energy_hourly", List.of(), 10);
        TabularResult result = executor.execute(sql, Map.of());
        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rejectsResultsExceedingTheByteBudget() throws UnsafeSqlException {
        ReadOnlyQueryExecutor tiny = new ReadOnlyQueryExecutor(readOnlyDataSource,
                new ReadOnlyQueryExecutor.QueryLimits(java.time.Duration.ofSeconds(3), 500, 512, 1_000_000.0));
        ValidatedSql sql = new ValidatedSql("SELECT building_id, meter_id FROM analytics.v_energy_hourly LIMIT 100", List.of(), 100);
        assertThatThrownBy(() -> tiny.execute(sql, Map.of()))
                .isInstanceOf(UnsafeSqlException.class)
                .hasFieldOrPropertyWithValue("errorCode", "QUERY_RESULT_TOO_LARGE");
    }

    @Test
    void databaseRejectsWritesEvenIfConstructedManually() {
        ValidatedSql insert = new ValidatedSql(
                "INSERT INTO analytics.v_alert_fact VALUES ('x','b','d','c','LOW',now(),'OPEN')", List.of(), 1);
        assertThatThrownBy(() -> executor.execute(insert, Map.of()))
                .isInstanceOf(UnsafeSqlException.class)
                .hasFieldOrPropertyWithValue("errorCode", "QUERY_READ_ONLY_DENIED");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), "smartpark_analytics_ro", "test-ro-pass")) {
            assertThat(connection.isReadOnly()).isFalse(); // connection default; executor enforces per transaction
        } catch (Exception ignored) {
            // container already verified privilege denial in migration test
        }
    }

    @Test
    void cancelsQueriesThatExceedTheStatementTimeout() {
        ValidatedSql sleep = new ValidatedSql("SELECT pg_sleep(10) FROM analytics.v_alert_fact LIMIT 1", List.of(), 1);
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> executor.execute(sleep, Map.of()))
                .isInstanceOf(Exception.class);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(8000);
    }

    @Test
    void costGuardRejectsExpensivePlansAndAcceptsCheapOnes() throws Exception {
        QueryCostGuard guard = new QueryCostGuard(jdbcTemplate);
        String sql = "SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly GROUP BY building_id";

        double cheapThreshold = Double.MAX_VALUE;
        assertThat(guard.estimatedCost(sql, Map.of(), cheapThreshold).planCost()).isGreaterThan(0);

        assertThatThrownBy(() -> guard.estimatedCost(sql, Map.of(), 0.000001))
                .isInstanceOf(UnsafeSqlException.class)
                .hasFieldOrPropertyWithValue("errorCode", "QUERY_COST_EXCEEDED");
    }
}
