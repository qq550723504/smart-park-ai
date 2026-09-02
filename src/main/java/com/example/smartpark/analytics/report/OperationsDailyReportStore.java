package com.example.smartpark.analytics.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe bounded in-memory storage for report snapshots. */
public class OperationsDailyReportStore {

    public static final int MAX_REPORTS = 10;

    private final Duration retention;
    private final Clock clock;
    private final Map<UUID, OperationsDailyReport> reports = new LinkedHashMap<>();
    private boolean active;

    public OperationsDailyReportStore() {
        this(Duration.ofMinutes(30), Clock.systemUTC());
    }

    public OperationsDailyReportStore(Duration retention, Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized OperationsDailyReport create(UUID runId, Instant now) {
        evictExpired(now);
        if (reports.containsKey(runId)) throw new IllegalStateException("report already exists");
        OperationsDailyReport report = new OperationsDailyReport(runId, "RUNNING", now, now,
                OperationsDailyReportDefinition.sections().stream()
                        .map(OperationsDailyReport.SectionResult::pending).toList());
        reports.put(runId, report);
        return report;
    }

    public synchronized Optional<OperationsDailyReport> get(UUID runId) {
        evictExpired(clock.instant());
        return Optional.ofNullable(reports.get(runId));
    }

    public synchronized OperationsDailyReport update(OperationsDailyReport report) {
        evictExpired(clock.instant());
        if (!reports.containsKey(report.runId())) throw new NoSuchElementException("Unknown report: " + report.runId());
        reports.put(report.runId(), report);
        evictTerminalOverflow();
        return report;
    }

    public synchronized boolean tryAcquireRun() {
        if (active) return false;
        active = true;
        return true;
    }

    public synchronized void releaseRun() {
        active = false;
    }

    public synchronized boolean activeRun() {
        return active;
    }

    private void evictExpired(Instant now) {
        reports.entrySet().removeIf(entry -> isTerminal(entry.getValue().status())
                && !entry.getValue().updatedAt().plus(retention).isAfter(now));
    }

    private void evictTerminalOverflow() {
        while (reports.size() > MAX_REPORTS) {
            Iterator<Map.Entry<UUID, OperationsDailyReport>> iterator = reports.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<UUID, OperationsDailyReport> oldest = iterator.next();
            if (!isTerminal(oldest.getValue().status())) return;
            iterator.remove();
        }
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
    }
}
