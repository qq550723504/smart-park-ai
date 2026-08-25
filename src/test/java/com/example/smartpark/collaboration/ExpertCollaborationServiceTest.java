package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.*;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertCollaborationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);

    @Test void mapsAFailedSynthesisToARunFailureInsteadOfCompletion() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var service = service(publisher, (q) -> plan(), (p, f) ->
                new Synthesis(FindingStatus.FAILED, "所有专家均失败", List.of(), 0, List.of("experts unavailable")));
        var run = service.start("energy consumption");

        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);
        waitForEvent(publisher, run.runId(), ExecutionEventType.FAILED);
        assertThat(service.get(run.runId()).synthesis()).isNotNull();
        assertThat(publisher.history(run.runId())).extracting(e -> e.eventType())
                .contains(ExecutionEventType.FAILED);
        assertThat(publisher.history(run.runId())).extracting(e -> e.eventType())
                .doesNotContain(ExecutionEventType.COMPLETED);
    }

    @Test void runsAsynchronouslyAndPublishesTerminalCompletion() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var service = service(publisher, (q) -> plan(), (p, findings) ->
                new Synthesis(FindingStatus.SUPPORTED, "energy supported", List.of("energy:1"), .8, List.of()));
        var run = service.start("energy consumption");
        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.COMPLETED);
        waitForEvent(publisher, run.runId(), ExecutionEventType.COMPLETED);
        assertThat(publisher.history(run.runId())).extracting(e -> e.eventType())
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.NODE_STARTED,
                        ExecutionEventType.EXPERT_HANDOFF, ExecutionEventType.COMPLETED);
    }

    @Test void derivesFailureWhenEveryExpertBranchFailsBeforeTrustingSynthesisStatus() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var executor = Executors.newSingleThreadExecutor();
        AtomicBoolean synthesizerCalled = new AtomicBoolean();
        try {
            var failedGraph = new ExpertCollaborationGraph(Map.of(
                    ExpertDomain.ENERGY, assignment -> new ExpertFinding(ExpertDomain.ENERGY,
                            FindingStatus.FAILED, "energy lookup failed", List.of(), 0, List.of("retry")),
                    ExpertDomain.DEVICE, assignment -> new ExpertFinding(ExpertDomain.DEVICE,
                            FindingStatus.FAILED, "device lookup failed", List.of(), 0, List.of("retry")),
                    ExpertDomain.SECURITY, assignment -> new ExpertFinding(ExpertDomain.SECURITY,
                            FindingStatus.FAILED, "security lookup failed", List.of(), 0, List.of("retry"))),
                    Runnable::run);
            var service = new ExpertCollaborationService(q -> plan(), failedGraph,
                    (p, findings) -> {
                        synthesizerCalled.set(true);
                        return new Synthesis(FindingStatus.INSUFFICIENT_EVIDENCE, "model says insufficient",
                                List.of(), 0, List.of("more evidence"));
                    }, new CollaborationRunStore(Duration.ofMinutes(30), CLOCK), publisher,
                    executor, Duration.ofSeconds(2), CLOCK);

            var run = service.start("energy consumption");
            waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);
            assertThat(service.get(run.runId()).synthesis()).extracting(Synthesis::status)
                    .isEqualTo(FindingStatus.FAILED);
            assertThat(service.get(run.runId()).synthesis().conclusion()).isEqualTo("所有专家分支均失败");
            assertThat(synthesizerCalled).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void rejectsOverloadBeforeRegisteringOrPublishingARun() {
        AtomicInteger published = new AtomicInteger();
        com.example.smartpark.execution.ExecutionEventPublisher publisher =
                new com.example.smartpark.execution.ExecutionEventPublisher() {
                    @Override public com.example.smartpark.execution.model.ExecutionEvent publish(
                            com.example.smartpark.execution.model.ExecutionEvent event) {
                        published.incrementAndGet();
                        return event;
                    }
                    @Override public List<com.example.smartpark.execution.model.ExecutionEvent> history(
                            java.util.UUID runId) { return List.of(); }
                    @Override public Subscription subscribe(java.util.UUID runId,
                            java.util.function.Consumer<com.example.smartpark.execution.model.ExecutionEvent> consumer) {
                        return () -> { };
                    }
                    @Override public String status(java.util.UUID runId) { return "UNKNOWN"; }
                    @Override public void remove(java.util.UUID runId) { }
                };
        java.util.concurrent.ExecutorService rejectingExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        rejectingExecutor.shutdownNow();
        var graph = new ExpertCollaborationGraph(Map.of(
                ExpertDomain.ENERGY, assignment -> new ExpertFinding(ExpertDomain.ENERGY,
                        FindingStatus.SUPPORTED, "energy", List.of("energy:1"), .8, List.of()),
                ExpertDomain.DEVICE, assignment -> new ExpertFinding(ExpertDomain.DEVICE,
                        FindingStatus.SUPPORTED, "device", List.of("device:1"), .8, List.of()),
                ExpertDomain.SECURITY, assignment -> new ExpertFinding(ExpertDomain.SECURITY,
                        FindingStatus.SUPPORTED, "security", List.of("security:1"), .8, List.of())),
                Runnable::run);
        var service = new ExpertCollaborationService(question -> plan(), graph,
                (plan, findings) -> new Synthesis(FindingStatus.SUPPORTED, "ok",
                        List.of("energy:1"), .8, List.of()),
                new CollaborationRunStore(Duration.ofMinutes(30), CLOCK), publisher,
                rejectingExecutor, Duration.ofSeconds(2), CLOCK);

        assertThatThrownBy(() -> service.start("energy consumption"))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(published).hasValue(0);
    }

    @Test void publishesOneFailureWhenPlannerFails() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var service = service(publisher, (q) -> { throw new IllegalStateException("planner"); }, (p, f) -> null);
        var run = service.start("energy consumption");
        waitFor(() -> publisher.history(run.runId()).stream()
                .anyMatch(event -> event.eventType() == ExecutionEventType.FAILED));
        assertThat(publisher.history(run.runId())).extracting(e -> e.eventType())
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.NODE_STARTED, ExecutionEventType.FAILED);
    }

    @Test void preservesCompletedFindingsWhenSynthesisFails() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var service = service(publisher, (q) -> plan(), (p, f) -> {
            throw new IllegalStateException("synthesis hung or failed");
        });
        var run = service.start("energy consumption");
        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);

        // Expert work completed before the synthesis failure must survive.
        var failed = service.get(run.runId());
        assertThat(failed.findings()).hasSize(1);
        assertThat(failed.findings().get(0).domain()).isEqualTo(ExpertDomain.ENERGY);
    }

    @Test void lateCompletionCannotOverwriteOverallTimeout() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var releasePlanner = new java.util.concurrent.CountDownLatch(1);
        var service = service(publisher, (q) -> {
            try { releasePlanner.await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return plan();
        }, (p, f) -> new Synthesis(FindingStatus.SUPPORTED, "energy supported", List.of("energy:1"), .8, List.of()), Duration.ofMillis(30));
        var run = service.start("energy consumption");
        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);
        releasePlanner.countDown();
        Thread.sleep(100);
        assertThat(service.get(run.runId()).status()).isEqualTo(CollaborationRun.RunStatus.FAILED);
        assertThat(publisher.history(run.runId())).extracting(e -> e.eventType())
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.NODE_STARTED, ExecutionEventType.FAILED);
    }
    @Test void marksRunFailedWhenOverallTimeoutExpires() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var service = service(publisher, (q) -> {
            try { Thread.sleep(500); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", ex); }
            return plan();
        }, (p, f) -> null, Duration.ofMillis(30));
        var run = service.start("energy consumption");
        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);
        waitForEvent(publisher, run.runId(), ExecutionEventType.FAILED);
        assertThat(publisher.history(run.runId())).last().extracting(e -> e.eventType()).isEqualTo(ExecutionEventType.FAILED);
    }

    @Test void overallTimeoutInterruptsTheUnderlyingRunTask() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        CountDownLatch plannerStarted = new CountDownLatch(1);
        CountDownLatch blockPlanner = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        var service = service(publisher, question -> {
            plannerStarted.countDown();
            try {
                blockPlanner.await();
            } catch (InterruptedException timeoutCancellation) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("planner interrupted", timeoutCancellation);
            }
            return plan();
        }, (plan, findings) -> null, Duration.ofMillis(30));

        var run = service.start("energy consumption");
        assertThat(plannerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        waitFor(() -> service.get(run.runId()).status() == CollaborationRun.RunStatus.FAILED);

        waitFor(interrupted::get);
        assertThat(service.get(run.runId()).status()).isEqualTo(CollaborationRun.RunStatus.FAILED);
    }


    private static ExpertCollaborationService service(InMemoryExecutionEventPublisher publisher,
            ExpertCollaborationService.Planner planner, ExpertCollaborationService.Synthesizer synthesizer) {
        return service(publisher, planner, synthesizer, Duration.ofSeconds(2));
    }

    private static ExpertCollaborationService service(InMemoryExecutionEventPublisher publisher,
            ExpertCollaborationService.Planner planner, ExpertCollaborationService.Synthesizer synthesizer, Duration timeout) {
        var graph = new ExpertCollaborationGraph(Map.of(
                ExpertDomain.ENERGY, assignment -> new ExpertFinding(ExpertDomain.ENERGY, FindingStatus.SUPPORTED, "energy", List.of("energy:1"), .8, List.of()),
                ExpertDomain.DEVICE, assignment -> new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.SUPPORTED, "device", List.of("device:1"), .8, List.of()),
                ExpertDomain.SECURITY, assignment -> new ExpertFinding(ExpertDomain.SECURITY, FindingStatus.SUPPORTED, "security", List.of("security:1"), .8, List.of())),
                Runnable::run);
        return new ExpertCollaborationService(planner, graph, synthesizer,
                new CollaborationRunStore(Duration.ofMinutes(30), CLOCK), publisher,
                Executors.newCachedThreadPool(), timeout, CLOCK);
    }

    private static SupervisorPlan plan() {
        return new SupervisorPlan("energy consumption", Set.of(ExpertDomain.ENERGY), Map.of(ExpertDomain.ENERGY, "energy"), "energy");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void waitForEvent(InMemoryExecutionEventPublisher publisher, java.util.UUID runId,
            ExecutionEventType eventType) throws Exception {
        waitFor(() -> publisher.history(runId).stream().anyMatch(event -> event.eventType() == eventType));
    }
}
