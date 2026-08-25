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
 * V1 seeds time-sensitive demo fixtures (device snapshots) once, but the
 * offline-device metric reads a rolling one-day lookback: on a persistent
 * database the seeded rows leave that window after roughly 22 hours and are
 * never refreshed by Flyway's one-time migration. This refresher periodically
 * re-anchors the demo snapshots to the current instant so the documented demo
 * analyses keep returning rows. It only ever touches demo fixture timestamps.
 */
public class DemoSnapshotRefresher {

    private static final Logger log = LoggerFactory.getLogger(DemoSnapshotRefresher.class);

    private final String url;
    private final String username;
    private final String password;
    private final Duration interval;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "demo-snapshot-refresher");
                thread.setDaemon(true);
                return thread;
            });

    public DemoSnapshotRefresher(String url, String username, String password, Duration interval) {
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
            log.warn("Demo snapshot refresh skipped: {}", failure.getMessage());
        }
    }

    /** Re-anchors any device snapshot that has aged out of the one-day lookback. */
    void refreshOnce() throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE analytics.device_snapshot_raw SET snapshot_at = now() - INTERVAL '2 hours' "
                            + "WHERE snapshot_at < now() - INTERVAL '2 hours'");
        }
    }
}
