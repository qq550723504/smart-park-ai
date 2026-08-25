package com.example.smartpark.execution;

import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEventPublisherTest {

    private final InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();

    @Test
    void assignsContiguousSequencesWithinOneRun() {
        UUID runId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            publisher.publish(event(runId, "event " + i, false));
        }
        assertThat(publisher.history(runId)).extracting(ExecutionEvent::sequence)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void keepsRunsIndependent() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        publisher.publish(event(runA, "a1", false));
        publisher.publish(event(runB, "b1", false));
        publisher.publish(event(runA, "a2", false));
        assertThat(publisher.history(runA)).extracting(ExecutionEvent::sequence).containsExactly(1L, 2L);
        assertThat(publisher.history(runB)).extracting(ExecutionEvent::sequence).containsExactly(1L);
    }

    @Test
    void concurrentPublishersNeverDuplicateOrSkipSequences() throws Exception {
        UUID runId = UUID.randomUUID();
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            jobs.add(() -> {
                await(start);
                for (int i = 0; i < perThread; i++) {
                    publisher.publish(event(runId, "t" + threadIndex + "-" + i, false));
                }
            });
        }
        jobs.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<Long> sequences = publisher.history(runId).stream().map(ExecutionEvent::sequence).distinct().sorted().toList();
        assertThat(sequences).hasSize(threads * perThread)
                .startsWith(1L)
                .endsWith((long) (threads * perThread));
        assertThat(publisher.history(runId))
                .extracting(event -> event.safeSummary())
                .hasSize(threads * perThread);
        assertThat(publisher.history(runId).stream()
                .map(event -> {
                    String summary = event.safeSummary();
                    return Integer.parseInt(summary.substring(1, summary.indexOf('-'))) + "-"
                            + Integer.parseInt(summary.substring(summary.indexOf('-') + 1));
                })
                .distinct())
                .hasSize(threads * perThread);
    }

    @Test
    void subscriberReceivesHistoryThenLiveEvents() {
        UUID runId = UUID.randomUUID();
        publisher.publish(event(runId, "first", false));

        List<ExecutionEvent> received = new ArrayList<>();
        var subscription = publisher.subscribe(runId, received::add);
        publisher.publish(event(runId, "second", false));

        assertThat(received).extracting(ExecutionEvent::safeSummary).containsExactly("first", "second");
        subscription.close();
    }

    @Test
    void terminalEventCompletesTheStreamAndRejectsFurtherPublishing() {
        UUID runId = UUID.randomUUID();
        publisher.publish(event(runId, "working", false));
        List<ExecutionEvent> received = new ArrayList<>();
        var subscription = publisher.subscribe(runId, received::add);

        publisher.publish(event(runId, "done", true));

        assertThatThrownBy(() -> publisher.publish(event(runId, "after terminal", false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(received).extracting(ExecutionEvent::safeSummary).containsExactly("working", "done");
        assertThat(publisher.status(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void reportsClarificationPauseInsteadOfGenericRunningStatus() {
        UUID runId = UUID.randomUUID();
        publisher.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(),
                ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.UNDERSTANDING,
                ExecutionEventType.PAUSED, ExecutionStatus.NEEDS_CLARIFICATION,
                "需要澄清", null));

        assertThat(publisher.status(runId)).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test
    void historyQueryDoesNotCreateUnknownRuns() {
        UUID unknown = UUID.randomUUID();
        assertThat(publisher.history(unknown)).isEmpty();
        assertThatThrownBy(() -> publisher.subscribe(unknown, ignored -> {}))
                .as("subscribing to a run that was never published must fail instead of creating it")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evictsTerminalRunHistoriesAfterTheRetentionExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
        InMemoryExecutionEventPublisher ttl =
                new InMemoryExecutionEventPublisher(java.time.Duration.ofMinutes(10), clock);
        UUID finished = UUID.randomUUID();
        UUID running = UUID.randomUUID();

        ttl.publish(event(finished, "working", false));
        ttl.publish(event(running, "busy", false));
        ttl.publish(event(finished, "done", true));

        // Just before the retention deadline the terminal history stays replayable.
        clock.advance(java.time.Duration.ofMinutes(9));
        ttl.publish(event(UUID.randomUUID(), "trigger sweep", false));
        assertThat(ttl.status(finished)).isEqualTo("COMPLETED");
        assertThat(ttl.history(finished)).hasSize(2);

        // Past the retention, the sweep evicts only the expired terminal run.
        clock.advance(java.time.Duration.ofMinutes(2));
        ttl.publish(event(UUID.randomUUID(), "trigger sweep again", false));
        assertThat(ttl.history(finished)).isEmpty();
        assertThat(ttl.status(finished)).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> ttl.subscribe(finished, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-terminal runs are never evicted by age.
        assertThat(ttl.history(running)).hasSize(1);
        assertThat(ttl.status(running)).isEqualTo("RUNNING");
    }

    @Test
    void removeOnlyCleansUpTerminalRuns() {
        UUID finished = UUID.randomUUID();
        publisher.publish(event(finished, "done", true));
        publisher.remove(finished);
        assertThat(publisher.history(finished)).isEmpty();

        UUID running = UUID.randomUUID();
        publisher.publish(event(running, "busy", false));
        assertThatThrownBy(() -> publisher.remove(running))
                .isInstanceOf(IllegalStateException.class);
        assertThat(publisher.history(running)).isNotEmpty();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MutableClock extends java.time.Clock {
        private Instant instant;

        MutableClock(Instant instant) { this.instant = instant; }

        void advance(java.time.Duration duration) { instant = instant.plus(duration); }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static ExecutionEvent event(UUID runId, String summary, boolean terminal) {
        return new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(),
                ExecutionScenario.ALERT_WORKFLOW, "system", ExecutionStage.ANALYSIS,
                terminal ? ExecutionEventType.COMPLETED : ExecutionEventType.RUN_STARTED,
                terminal ? ExecutionStatus.SUCCEEDED : ExecutionStatus.RUNNING,
                summary, null);
    }
}
