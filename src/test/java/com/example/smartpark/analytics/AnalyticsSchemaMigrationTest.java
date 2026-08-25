package com.example.smartpark.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.core.io.ClassPathResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Establishes the real analytics data boundary: four whitelisted views must
 * exist, the application's read-only role must be able to SELECT them and must
 * be unable to write anything — enforced by database privileges themselves.
 */
@Testcontainers
class AnalyticsSchemaMigrationTest {

    private static final String RO_USER = "smartpark_analytics_ro";
    private static final String RO_PASSWORD = "test-ro-pass";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("smartpark");

    private static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("analyticsRoPassword", RO_PASSWORD))
                .load()
                .migrate();
    }

    @Test
    void whitelistedViewsExistAndReadOnlyRoleCannotWrite() throws SQLException {
        migrate();

        try (Connection ro = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RO_USER, RO_PASSWORD)) {
            for (String view : new String[] {
                    "v_energy_hourly", "v_alert_fact", "v_device_snapshot", "v_parking_daily" }) {
                try (var statement = ro.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM analytics." + view)) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(0);
                }
            }

            // The read-only account is denied every kind of write at privilege level.
            assertThatThrownBy(() -> exec(ro, "INSERT INTO analytics.v_alert_fact VALUES ('a-x','b','d','c','LOW',now(),'OPEN')"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> exec(ro, "UPDATE analytics.v_parking_daily SET entries = 0"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> exec(ro, "DELETE FROM analytics.v_device_snapshot"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> exec(ro, "CREATE TABLE analytics.evil(id int)"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("permission denied");

            // Night energy definition stays fixed: 22:00–06:00 rows are selectable via the view.
            try (var statement = ro.prepareStatement(
                    "SELECT COUNT(*) FROM analytics.v_energy_hourly WHERE EXTRACT(HOUR FROM hour_ts) >= 22 OR EXTRACT(HOUR FROM hour_ts) < 6");
                 ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }

            // Seeded demo facts are visible through the views.
            try (var statement = ro.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM analytics.v_alert_fact")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    void seedsDeviceSnapshotsInsideTheRollingOneDayLookback() throws Exception {
        // device_offline_count uses a rolling one-day lookback; snapshots
        // anchored to a fixed wall-clock time can already be more than 24h old
        // when the migration runs, so the seeded facts must be recent.
        migrate();
        try (var ro = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RO_USER, RO_PASSWORD);
             var statement = ro.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT MIN(snapshot_at), MAX(snapshot_at), now() FROM analytics.v_device_snapshot")) {
            assertThat(rs.next()).isTrue();
            var oldest = rs.getObject(1, java.time.OffsetDateTime.class);
            var reference = rs.getObject(3, java.time.OffsetDateTime.class);
            assertThat(oldest).isAfter(reference.minus(java.time.Duration.ofHours(24)));
        }
    }

    @Test
    void snapshotRefresherReanchorsAgedDemoSnapshots() throws Exception {
        // On a persistent database the one-time V1 seeds age out of the rolling
        // one-day lookback; the refresher must pull them back inside it.
        migrate();
        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("UPDATE analytics.device_snapshot_raw "
                    + "SET snapshot_at = now() - INTERVAL '3 days'");
        }

        var properties = new com.example.smartpark.analytics.AnalyticsProperties();
        properties.getDatasource().setUrl(POSTGRES.getJdbcUrl());
        properties.getDatasource().setAdminUsername(POSTGRES.getUsername());
        properties.getDatasource().setAdminPassword(POSTGRES.getPassword());
        new com.example.smartpark.analytics.DemoSnapshotRefresher(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getAdminUsername(),
                properties.getDatasource().getAdminPassword(),
                java.time.Duration.ofHours(1)).refreshOnce();

        try (var ro = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RO_USER, RO_PASSWORD);
             var statement = ro.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT MIN(snapshot_at), now() FROM analytics.v_device_snapshot")) {
            assertThat(rs.next()).isTrue();
            var oldest = rs.getObject(1, java.time.OffsetDateTime.class);
            var reference = rs.getObject(2, java.time.OffsetDateTime.class);
            assertThat(oldest).isAfter(reference.minus(java.time.Duration.ofHours(24)));
        }
    }

    @Test
    void demoEnergySeedUsesARecentRelativeDateAnchor() throws Exception {
        String migration = new String(new ClassPathResource(
                "db/migration/V1__analytics_readonly_schema.sql").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(migration).contains("CURRENT_DATE");
        assertThat(migration).doesNotContain("TIMESTAMPTZ '2026-08-");
    }

    private void exec(Connection connection, String sql) {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }
}
