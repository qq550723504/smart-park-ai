package com.example.smartpark.analytics.report;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsDailyReportStoreTest {

    private final Instant now = Instant.parse("2026-09-02T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final OperationsDailyReportStore store = new OperationsDailyReportStore(Duration.ofMinutes(30), clock);

    @Test
    void allowsOnlyOneActiveReportAndReturnsImmutableState() {
        UUID id = UUID.randomUUID();
        OperationsDailyReport created = store.create(id, now);
        assertThat(store.tryAcquireRun()).isTrue();
        assertThat(store.tryAcquireRun()).isFalse();

        assertThatThrownBy(() -> created.sections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        store.releaseRun();
        assertThat(store.activeRun()).isFalse();
        assertThat(store.tryAcquireRun()).isTrue();
    }

    @Test
    void evictsOldestTerminalReportsAfterTheBound() {
        UUID first = null;
        for (int i = 0; i < 11; i++) {
            UUID id = UUID.randomUUID();
            if (i == 0) first = id;
            OperationsDailyReport report = store.create(id, now);
            store.update(report.withStatus("COMPLETED", now.plusSeconds(i + 1)));
        }
        assertThat(store.get(first)).isEmpty();
    }

    @Test
    void expiresTerminalReportsAfterRetention() {
        UUID id = UUID.randomUUID();
        OperationsDailyReport report = store.create(id, now)
                .withStatus("FAILED", now);
        store.update(report);
        assertThat(store.get(id)).isPresent();

        Clock later = Clock.fixed(now.plus(Duration.ofMinutes(31)), ZoneOffset.UTC);
        OperationsDailyReportStore laterStore = new OperationsDailyReportStore(Duration.ofMinutes(30), later);
        laterStore.create(id, now);
        laterStore.update(report.withStatus("FAILED", now));
        assertThat(laterStore.get(id)).isEmpty();
    }
}
