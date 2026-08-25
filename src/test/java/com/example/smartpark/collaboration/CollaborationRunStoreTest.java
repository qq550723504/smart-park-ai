package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.CollaborationRun;
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
 * Terminal collaboration runs retain their full plan/findings/synthesis; like
 * the analytics run store they are kept replayable only for a bounded window
 * and then swept. RUNNING and NEEDS_CLARIFICATION records are never evicted.
 */
class CollaborationRunStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void evictsTerminalRunsAfterTheirRetentionExpires() {
        MutableClock clock = new MutableClock(NOW);
        var store = new CollaborationRunStore(Duration.ofMinutes(10), clock);
        UUID completed = put(store, "COMPLETED");
        UUID failed = put(store, "FAILED");

        clock.advance(Duration.ofMinutes(9));
        store.save(run(UUID.randomUUID(), "COMPLETED"));
        assertThat(store.get(completed)).isNotNull();
        assertThat(store.get(failed)).isNotNull();

        clock.advance(Duration.ofMinutes(2));
        store.save(run(UUID.randomUUID(), "COMPLETED"));
        assertThatThrownByIsNoSuchElement(store, completed);
        assertThatThrownByIsNoSuchElement(store, failed);
    }

    @Test
    void nonTerminalRunsAreNeverEvicted() {
        MutableClock clock = new MutableClock(NOW);
        var store = new CollaborationRunStore(Duration.ofMinutes(10), clock);
        UUID running = put(store, "RUNNING");
        UUID paused = put(store, "NEEDS_CLARIFICATION");

        clock.advance(Duration.ofHours(1));
        store.save(run(UUID.randomUUID(), "COMPLETED"));

        assertThat(store.get(running)).isNotNull();
        assertThat(store.get(paused)).isNotNull();
    }

    private static void assertThatThrownByIsNoSuchElement(CollaborationRunStore store, UUID id) {
        try {
            store.get(id);
            throw new AssertionError("expected eviction of " + id);
        } catch (java.util.NoSuchElementException expected) {
            // swept as intended
        }
    }

    private static CollaborationRun run(UUID id, String status) {
        return new CollaborationRun(id, "问题", CollaborationRun.RunStatus.valueOf(status),
                null, List.of(), null, null, NOW);
    }

    private static UUID put(CollaborationRunStore store, String status) {
        var run = run(UUID.randomUUID(), status);
        store.save(run);
        return run.runId();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }

        void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
