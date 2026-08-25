package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completed analysis runs permanently retaining full result tables would
 * monotonically consume heap; the store keeps terminal records replayable for
 * a bounded window and then sweeps them, exactly like the execution-event
 * publisher. Non-terminal records are never swept.
 */
class AnalysisRunStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void evictsCompletedRunsAfterTheirRetentionExpires() {
        MutableClock clock = new MutableClock(NOW);
        AnalysisRunStore store = new AnalysisRunStore(Duration.ofMinutes(10), clock);
        UUID finished = put(store, "COMPLETED");

        // Just before the deadline the result stays replayable.
        clock.advance(Duration.ofMinutes(9));
        store.put(record("other"));
        assertThat(store.get(finished)).isNotNull();

        // Past the retention the sweep removes it.
        clock.advance(Duration.ofMinutes(2));
        store.put(record("trigger"));
        assertThat(store.get(finished)).isNull();
    }

    @Test
    void failedRunsAreEvictedTooButRunningAndPausedRunsAreNeverTouched() {
        MutableClock clock = new MutableClock(NOW);
        AnalysisRunStore store = new AnalysisRunStore(Duration.ofMinutes(10), clock);
        UUID failed = put(store, "FAILED");
        UUID running = put(store, "RUNNING");
        UUID paused = put(store, "NEEDS_CLARIFICATION");

        clock.advance(Duration.ofHours(1));
        store.put(record("trigger"));

        assertThat(store.get(failed)).isNull();
        assertThat(store.get(running)).isNotNull();
        assertThat(store.get(paused)).isNotNull();
    }

    @Test
    void terminalRetentionIsMeasuredFromTheTransitionNotTheCreationTime() {
        MutableClock clock = new MutableClock(NOW);
        AnalysisRunStore store = new AnalysisRunStore(Duration.ofMinutes(10), clock);
        // Created long ago but only just completed: retention starts now.
        UUID finished = UUID.randomUUID();
        store.put(new AnalysisRunStore.RunRecord(finished, "问题", "COMPLETED",
                List.of(), List.of(), "", 0, false, 5, null,
                NOW.minus(Duration.ofHours(2)), NOW, List.of(), List.of()));

        clock.advance(Duration.ofMinutes(9));
        store.put(record("trigger"));
        assertThat(store.get(finished)).as("freshly completed run stays replayable").isNotNull();

        clock.advance(Duration.ofMinutes(2));
        store.put(record("trigger"));
        assertThat(store.get(finished)).isNull();
    }

    private static AnalysisRunStore.RunRecord record(String status) {
        return new AnalysisRunStore.RunRecord(UUID.randomUUID(), "问题", status,
                List.of(), List.of(), "", 0, false, 5, null, NOW, NOW, List.of(), List.of());
    }

    private static UUID put(AnalysisRunStore store, String status) {
        var record = record(status);
        store.put(record);
        return record.runId();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }

        void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
