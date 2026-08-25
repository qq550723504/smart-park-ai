package com.example.smartpark.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * V1 seeds time-sensitive demo fixtures (energy, alerts, device snapshots,
 * parking) once, but every catalog metric reads a runtime-relative lookback
 (seven days by default, one day for device snapshots): on a persistent
 * database the seeded rows leave those windows and the documented demo
 * analyses start returning empty results. This refresher periodically
 * re-anchors ALL V1 demo facts to the current instant so the shipped demos
 * keep returning rows. Registration is explicitly opt-in and every update is
 * restricted to the fixture identifiers from V1, so real datasets stay
 * untouched even if they coincidentally share a building or zone name.
 */
public class DemoDataRefresher {

    private static final Logger log = LoggerFactory.getLogger(DemoDataRefresher.class);

    private static final String DEVICE_IDS =
            "('AC-B1-07', 'PWR-B1-02', 'LFT-B1-01', 'HUM-B2-11', 'DR-B2-01', 'AC-B3-03', 'CAM-B3-05')";
    private static final String ALERT_IDS =
            "('ALT-TEMP-001', 'ALT-PWR-002', 'ALT-HUM-003', 'ALT-DOOR-004', 'ALT-TEMP-005')";
    private static final String PARKING_ZONES = "parking_zone IN ('ZONE-A', 'ZONE-B')";

    private final String url;
    private final String username;
    private final String password;
    private final Duration interval;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "demo-data-refresher");
                thread.setDaemon(true);
                return thread;
            });

    public DemoDataRefresher(String url, String username, String password, Duration interval) {
        this.url = Objects.requireNonNull(url, "url");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.interval = interval;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::refreshSafely,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void refreshSafely() {
        try {
            refreshOnce();
        } catch (Exception failure) {
            log.warn("Demo data refresh skipped: {}", failure.getMessage());
        }
    }

    /**
     * Re-anchors only the V1 demo fixtures that aged out of the runtime
     * lookback windows. Energy/alert/parking fixtures are deterministically
     * regenerated against CURRENT_DATE (same generator and values as V1) —
     * shifting timestamps in place cannot be done safely because PostgreSQL
     * rewrites rows one at a time and a forward shift collides with not-yet-
     * moved primary keys. Device snapshots pin to two hours ago.
     */
    void refreshOnce() throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE analytics.device_snapshot_raw SET snapshot_at = now() - INTERVAL '2 hours' "
                            + "WHERE snapshot_at < now() - INTERVAL '2 hours' "
                            + "AND device_id IN " + DEVICE_IDS);
            // Energy: only rows matching the exact V1 demo shape are replaced,
            // so a real deployment using its own MTR-* meters is untouched.
            statement.executeUpdate(
                    "DELETE FROM analytics.energy_hourly_raw WHERE meter_id ~ '^MTR-[0-9]+-[0-9]+$' "
                            + "AND building_id IN ('B1', 'B2', 'B3')");
            statement.executeUpdate(ENERGY_SEED);
            statement.executeUpdate("DELETE FROM analytics.alert_fact_raw WHERE alert_id IN " + ALERT_IDS);
            statement.executeUpdate(ALERT_SEED);
            statement.executeUpdate("DELETE FROM analytics.parking_daily_raw WHERE " + PARKING_ZONES);
            statement.executeUpdate(PARKING_SEED);
        }
    }

    private static final String ENERGY_SEED =
            "INSERT INTO analytics.energy_hourly_raw (building_id, meter_id, reading_at, kwh, baseline_kwh, peak_kw) "
            + "SELECT 'B' || b, 'MTR-' || b || '-' || m, "
            + "((CURRENT_DATE - 4)::timestamp AT TIME ZONE 'Asia/Shanghai') + make_interval(hours => (d * 24 + h)), "
            + "CASE WHEN h >= 22 OR h < 6 THEN 4.5 + d + b ELSE 18.0 + d * 2 + b END + m, "
            + "15.0 + b * 2, "
            + "CASE WHEN h BETWEEN 9 AND 19 THEN 42.0 + b * 3 ELSE 12.0 + b END "
            + "FROM generate_series(1, 3) AS b, generate_series(1, 2) AS m, "
            + "generate_series(0, 4) AS d, generate_series(0, 23) AS h ON CONFLICT DO NOTHING";

    private static final String ALERT_SEED =
            "INSERT INTO analytics.alert_fact_raw (alert_id, building_id, device_id, category, risk_level, occurred_at, status) VALUES "
            + "('ALT-TEMP-001', 'B1', 'AC-B1-07', 'TEMPERATURE', 'HIGH',   (((CURRENT_DATE - 3)::timestamp + TIME '09:15') AT TIME ZONE 'Asia/Shanghai'), 'OPEN'), "
            + "('ALT-PWR-002',  'B1', 'PWR-B1-02', 'POWER',      'LOW',    (((CURRENT_DATE - 3)::timestamp + TIME '14:40') AT TIME ZONE 'Asia/Shanghai'), 'RESOLVED'), "
            + "('ALT-HUM-003',  'B2', 'HUM-B2-11', 'HUMIDITY',   'MEDIUM', (((CURRENT_DATE - 2)::timestamp + TIME '03:05') AT TIME ZONE 'Asia/Shanghai'), 'OPEN'), "
            + "('ALT-DOOR-004', 'B2', 'DR-B2-01',  'ACCESS',     'HIGH',   (((CURRENT_DATE - 2)::timestamp + TIME '22:30') AT TIME ZONE 'Asia/Shanghai'), 'OPEN'), "
            + "('ALT-TEMP-005', 'B3', 'AC-B3-03',  'TEMPERATURE', 'LOW',   (((CURRENT_DATE - 1)::timestamp + TIME '11:20') AT TIME ZONE 'Asia/Shanghai'), 'RESOLVED') "
            + "ON CONFLICT DO NOTHING";

    private static final String PARKING_SEED =
            "INSERT INTO analytics.parking_daily_raw (stat_date, parking_zone, entries, peak_occupancy, capacity) VALUES "
            + "(CURRENT_DATE - 4, 'ZONE-A', 812, 340, 400), "
            + "(CURRENT_DATE - 4, 'ZONE-B', 455, 180, 250), "
            + "(CURRENT_DATE - 3, 'ZONE-A', 876, 388, 400), "
            + "(CURRENT_DATE - 3, 'ZONE-B', 462, 205, 250), "
            + "(CURRENT_DATE - 2, 'ZONE-A', 901, 397, 400), "
            + "(CURRENT_DATE - 2, 'ZONE-B', 470, 214, 250), "
            + "(CURRENT_DATE - 1, 'ZONE-A', 604, 266, 400), "
            + "(CURRENT_DATE - 1, 'ZONE-B', 310, 141, 250) "
            + "ON CONFLICT DO NOTHING";
}
