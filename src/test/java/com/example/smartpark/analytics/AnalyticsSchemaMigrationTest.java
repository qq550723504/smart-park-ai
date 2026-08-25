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
                .load()
                .migrate();
        // Simulate the application's startup credential binding (V1 creates the
        // role password-less; AnalyticsRoleCredentials sets its runtime password).
        com.example.smartpark.analytics.AnalyticsRoleCredentials.sync(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), RO_PASSWORD);
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
                     "SELECT MIN(snapshot_at), MAX(snapshot_at), now() FROM analytics.v_device_snapshot "
                             + "WHERE device_id IN ('AC-B1-07', 'PWR-B1-02', 'LFT-B1-01', "
                             + "'HUM-B2-11', 'DR-B2-01', 'AC-B3-03', 'CAM-B3-05')")) {
            assertThat(rs.next()).isTrue();
            var oldest = rs.getObject(1, java.time.OffsetDateTime.class);
            var reference = rs.getObject(3, java.time.OffsetDateTime.class);
            assertThat(oldest).isAfter(reference.minus(java.time.Duration.ofHours(24)));
        }
    }

    @Test
    void refresherReanchorsAllAgedDemoFactsWithoutTouchingRealData() throws Exception {
        // On a persistent database the one-time V1 seeds age out of every
        // runtime-relative lookback (7 days for energy/alerts/parking, 1 day
        // for device snapshots); the opt-in demo refresher may pull only those
        // named fixtures back inside their windows and must never rewrite real
        // data that happens to share a building, zone or device name.
        migrate();
        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("UPDATE analytics.device_snapshot_raw "
                    + "SET snapshot_at = now() - INTERVAL '3 days'");
            statement.execute("INSERT INTO analytics.device_snapshot_raw "
                    + "(device_id, building_id, device_type, status, snapshot_at) VALUES "
                    + "('REAL-DEVICE-001', 'REAL', 'HVAC', 'OFFLINE', now() - INTERVAL '3 days') "
                    + "ON CONFLICT (device_id) DO UPDATE SET snapshot_at = EXCLUDED.snapshot_at");
            statement.execute("UPDATE analytics.energy_hourly_raw "
                    + "SET reading_at = reading_at - INTERVAL '30 days' WHERE meter_id LIKE 'MTR-%'");
            // A real energy row sharing a demo-shaped meter id must survive.
            statement.execute("INSERT INTO analytics.energy_hourly_raw "
                    + "(building_id, meter_id, reading_at, kwh, baseline_kwh, peak_kw) VALUES "
                    + "('REAL', 'MTR-REAL-9', now() - INTERVAL '2 days', 1, 1, 1) ");
            statement.execute("UPDATE analytics.alert_fact_raw "
                    + "SET occurred_at = occurred_at - INTERVAL '30 days' WHERE alert_id LIKE 'ALT-%'");
            statement.execute("UPDATE analytics.parking_daily_raw "
                    + "SET stat_date = stat_date - INTERVAL '30 days' WHERE parking_zone IN ('ZONE-A', 'ZONE-B')");
        }

        var properties = new com.example.smartpark.analytics.AnalyticsProperties();
        properties.getDatasource().setUrl(POSTGRES.getJdbcUrl());
        properties.getDatasource().setAdminUsername(POSTGRES.getUsername());
        properties.getDatasource().setAdminPassword(POSTGRES.getPassword());
        new com.example.smartpark.analytics.DemoDataRefresher(
                properties.getDatasource().getUrl(),
                properties.getDatasource().getAdminUsername(),
                properties.getDatasource().getAdminPassword(),
                java.time.Duration.ofHours(1)).refreshOnce();

        try (var ro = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RO_USER, RO_PASSWORD)) {
            try (var statement = ro.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT MIN(snapshot_at), now() FROM analytics.v_device_snapshot "
                                 + "WHERE device_id <> 'REAL-DEVICE-001'")) {
                assertThat(rs.next()).isTrue();
                var oldestDemo = rs.getObject(1, java.time.OffsetDateTime.class);
                var reference = rs.getObject(2, java.time.OffsetDateTime.class);
                assertThat(oldestDemo).isAfter(reference.minus(java.time.Duration.ofHours(24)));
            }
            try (var statement = ro.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT snapshot_at, now() FROM analytics.v_device_snapshot "
                                 + "WHERE device_id = 'REAL-DEVICE-001'")) {
                assertThat(rs.next()).isTrue();
                var realSnapshot = rs.getObject(1, java.time.OffsetDateTime.class);
                var reference = rs.getObject(2, java.time.OffsetDateTime.class);
                assertThat(realSnapshot).isBefore(reference.minus(java.time.Duration.ofHours(48)));
            }
        }

        // Energy fixtures are back inside the seven-day lookback; a real row
        // with a demo-shaped meter id is left exactly where it was. The admin
        // account reads the raw fixture tables — the read-only role must not.
        try (var admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT MAX(reading_at), now() FROM analytics.energy_hourly_raw WHERE meter_id LIKE 'MTR-%'")) {
                assertThat(rs.next()).isTrue();
                var newestDemo = rs.getObject(1, java.time.OffsetDateTime.class);
                var reference = rs.getObject(2, java.time.OffsetDateTime.class);
                assertThat(newestDemo).isAfter(reference.minus(java.time.Duration.ofDays(7)));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT reading_at FROM analytics.energy_hourly_raw WHERE meter_id = 'MTR-REAL-9'")) {
                assertThat(rs.next()).isTrue();
                var realReading = rs.getObject(1, java.time.OffsetDateTime.class);
                assertThat(realReading).isBefore(java.time.OffsetDateTime.now().minus(java.time.Duration.ofDays(1)));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT MAX(occurred_at), now() FROM analytics.alert_fact_raw WHERE alert_id LIKE 'ALT-%'")) {
                assertThat(rs.next()).isTrue();
                var newestAlert = rs.getObject(1, java.time.OffsetDateTime.class);
                var reference = rs.getObject(2, java.time.OffsetDateTime.class);
                assertThat(newestAlert).isAfter(reference.minus(java.time.Duration.ofDays(7)));
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT MAX(stat_date), CURRENT_DATE FROM analytics.parking_daily_raw "
                            + "WHERE parking_zone IN ('ZONE-A', 'ZONE-B')")) {
                assertThat(rs.next()).isTrue();
                var newestParking = rs.getObject(1, java.time.LocalDate.class);
                var today = rs.getObject(2, java.time.LocalDate.class);
                assertThat(newestParking).isEqualTo(today.minusDays(1));
            }
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

    @Test
    void bindsTheRuntimeRoPasswordSafelyEvenWithAnApostrophe() throws Exception {
        // Flyway substitutes placeholders before PostgreSQL parses the script,
        // so interpolating a generated secret like "abc'def" into migration SQL
        // used to break startup. The role is now created password-less and the
        // runtime credential is bound via a JDBC parameter — no interpolation
        // path remains, so any password character works.
        try (PostgreSQLContainer<?> apostropheDatabase = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("analytics_apostrophe")) {
            apostropheDatabase.start();
            Flyway.configure()
                    .dataSource(apostropheDatabase.getJdbcUrl(), apostropheDatabase.getUsername(),
                            apostropheDatabase.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            String trickyPassword = "abc'def";
            com.example.smartpark.analytics.AnalyticsRoleCredentials.sync(
                    apostropheDatabase.getJdbcUrl(), apostropheDatabase.getUsername(),
                    apostropheDatabase.getPassword(), trickyPassword);
            try (Connection ro = DriverManager.getConnection(
                    apostropheDatabase.getJdbcUrl(), RO_USER, trickyPassword);
                 var statement = ro.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT COUNT(*) FROM analytics.v_energy_hourly")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    void v2RemovesInheritedPublicPrivilegesFromExistingReadOnlyRole() throws Exception {
        try (PostgreSQLContainer<?> upgradeDatabase = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("analytics_upgrade")) {
            upgradeDatabase.start();
            Flyway.configure()
                    .dataSource(upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .locations("classpath:db/migration")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("1"))
                    .load()
                    .migrate();
            com.example.smartpark.analytics.AnalyticsRoleCredentials.sync(
                    upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                    upgradeDatabase.getPassword(), RO_PASSWORD);

            try (Connection admin = DriverManager.getConnection(
                    upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(), upgradeDatabase.getPassword())) {
                exec(admin, "CREATE TABLE public.public_leak(secret text)");
                exec(admin, "INSERT INTO public.public_leak VALUES ('must-not-leak')");
                exec(admin, "GRANT SELECT ON public.public_leak TO PUBLIC");
                exec(admin, "GRANT CONNECT, CREATE, TEMPORARY ON DATABASE analytics_upgrade TO " + RO_USER);
            }
            try (Connection ro = DriverManager.getConnection(
                    upgradeDatabase.getJdbcUrl(), RO_USER, RO_PASSWORD);
                 var statement = ro.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT secret FROM public.public_leak")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("must-not-leak");
            }

            Flyway.configure()
                    .dataSource(upgradeDatabase.getJdbcUrl(), upgradeDatabase.getUsername(),
                            upgradeDatabase.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection ro = DriverManager.getConnection(
                    upgradeDatabase.getJdbcUrl(), RO_USER, RO_PASSWORD)) {
                assertThatThrownBy(() -> exec(ro, "SELECT secret FROM public.public_leak"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "CREATE TABLE public.evil(id int)"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "CREATE TABLE analytics.evil(id int)"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "CREATE SCHEMA direct_privilege_leak"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "CREATE TEMP TABLE direct_temp_leak(id int)"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> exec(ro, "SELECT * FROM analytics.energy_hourly_raw"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("permission denied");
                try (var statement = ro.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM analytics.v_energy_hourly")) {
                    assertThat(rs.next()).isTrue();
                }
            }
        }
    }

    private void exec(Connection connection, String sql) {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }
}
