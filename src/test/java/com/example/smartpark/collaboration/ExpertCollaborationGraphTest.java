package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertCollaborationGraphTest {
    @Test void dispatchesOnlySelectedExperts() {
        Map<ExpertDomain, Integer> calls = new ConcurrentHashMap<>();
        ExpertCollaborationGraph graph = graph(calls, (domain, assignment) -> finding(domain));
        SupervisorPlan plan = plan(ExpertDomain.ENERGY);
        List<ExpertFinding> findings = graph.execute(plan);
        assertThat(findings).extracting(ExpertFinding::domain).containsExactly(ExpertDomain.ENERGY);
        assertThat(calls).containsEntry(ExpertDomain.ENERGY, 1).doesNotContainKeys(ExpertDomain.DEVICE, ExpertDomain.SECURITY);
    }

    @Test void executesSelectedExpertsConcurrentlyAndSortsResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        Map<ExpertDomain, Integer> calls = new ConcurrentHashMap<>();
        ExpertCollaborationGraph graph = graph(calls, (domain, assignment) -> {
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return finding(domain);
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        var result = caller.submit(() -> graph.execute(plan(ExpertDomain.SECURITY, ExpertDomain.ENERGY, ExpertDomain.DEVICE)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(result.get(2, TimeUnit.SECONDS)).extracting(ExpertFinding::domain)
                .containsExactly(ExpertDomain.ENERGY, ExpertDomain.DEVICE, ExpertDomain.SECURITY);
        caller.shutdownNow();
    }

    @Test void preservesOtherFindingsWhenOneExpertTimesOut() {
        // Generous margins so the intended timeout path stays deterministic
        // even on a heavily loaded machine running the whole suite.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            EnumMap<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
            experts.put(ExpertDomain.ENERGY, assignment -> {
                try { Thread.sleep(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return finding(ExpertDomain.ENERGY);
            });
            experts.put(ExpertDomain.DEVICE, assignment -> finding(ExpertDomain.DEVICE));
            experts.put(ExpertDomain.SECURITY, assignment -> finding(ExpertDomain.SECURITY));
            var graph = new ExpertCollaborationGraph(experts, executor, Duration.ofMillis(200));

            var findings = graph.execute(new SupervisorPlan("energy and device",
                    EnumSet.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE),
                    Map.of(ExpertDomain.ENERGY, "energy", ExpertDomain.DEVICE, "device"), "two domains"));

            assertThat(findings).extracting(ExpertFinding::domain)
                    .containsExactly(ExpertDomain.ENERGY, ExpertDomain.DEVICE);
            assertThat(findings.get(0).status()).isEqualTo(FindingStatus.FAILED);
            assertThat(findings.get(1).status()).isEqualTo(FindingStatus.SUPPORTED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void cancelsEveryBranchWithinOneExpertTimeoutOfTheCommonSubmission() {
        // Timeouts must be measured from the shared submission deadline: with
        // sequential per-await waits, three hung branches would each consume
        // their own full expert timeout and occupy executor threads far beyond
        // the configured limit.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            EnumMap<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
            for (ExpertDomain domain : ExpertDomain.values()) {
                experts.put(domain, assignment -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return finding(domain);
                });
            }
            var graph = new ExpertCollaborationGraph(experts, executor, Duration.ofMillis(300));

            long startNanos = System.nanoTime();
            var findings = graph.execute(plan(ExpertDomain.SECURITY, ExpertDomain.ENERGY, ExpertDomain.DEVICE));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(findings).extracting(ExpertFinding::status).containsOnly(FindingStatus.FAILED);
            // Sequential awaits would need at least 3 x 300ms; a shared deadline
            // cancels every branch after roughly one timeout window.
            assertThat(elapsedMs).as("all branches share one submission deadline").isLessThan(700);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void interruptsTimedOutExpertInvocation() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            EnumMap<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
            experts.put(ExpertDomain.ENERGY, assignment -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return finding(ExpertDomain.ENERGY);
            });
            experts.put(ExpertDomain.DEVICE, assignment -> finding(ExpertDomain.DEVICE));
            experts.put(ExpertDomain.SECURITY, assignment -> finding(ExpertDomain.SECURITY));

            var graph = new ExpertCollaborationGraph(experts, executor, Duration.ofMillis(50));
            var findings = graph.execute(plan(ExpertDomain.ENERGY));

            assertThat(findings.get(0).status()).isEqualTo(FindingStatus.FAILED);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
    @Test void retainsSuccessfulFindingsWhenOneExpertFails() {
        Map<ExpertDomain, Integer> calls = new ConcurrentHashMap<>();
        ExpertCollaborationGraph graph = graph(calls, (domain, assignment) -> {
            if (assignment.equals("fail")) throw new IllegalStateException("boom");
            return finding(domain);
        });
        SupervisorPlan plan = new SupervisorPlan("cross", EnumSet.of(ExpertDomain.ENERGY, ExpertDomain.DEVICE),
                Map.of(ExpertDomain.ENERGY, "ok", ExpertDomain.DEVICE, "fail"), "cross");
        assertThat(graph.execute(plan)).extracting(ExpertFinding::status)
                .containsExactly(FindingStatus.SUPPORTED, FindingStatus.FAILED);
    }

    @Test void turnsARejectedExpertBranchIntoAFailedFinding() {
        EnumMap<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
        for (ExpertDomain domain : ExpertDomain.values()) experts.put(domain, assignment -> finding(domain));
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor rejectingAfterOne = command -> {
            if (submissions.getAndIncrement() == 0) command.run();
            else throw new RejectedExecutionException("expert queue full");
        };

        var findings = new ExpertCollaborationGraph(experts, rejectingAfterOne)
                .execute(plan(ExpertDomain.ENERGY, ExpertDomain.DEVICE));

        assertThat(findings).extracting(ExpertFinding::status)
                .containsExactly(FindingStatus.SUPPORTED, FindingStatus.FAILED);
    }

    private static ExpertCollaborationGraph graph(Map<ExpertDomain, Integer> calls, java.util.function.BiFunction<ExpertDomain, String, ExpertFinding> fn) {
        EnumMap<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
        for (ExpertDomain domain : ExpertDomain.values()) {
            experts.put(domain, assignment -> {
                calls.merge(domain, 1, Integer::sum);
                return fn.apply(domain, assignment);
            });
        }
        return new ExpertCollaborationGraph(experts, Executors.newFixedThreadPool(3));
    }

    private static SupervisorPlan plan(ExpertDomain... domains) {
        EnumMap<ExpertDomain, String> assignments = new EnumMap<>(ExpertDomain.class);
        for (ExpertDomain domain : domains) assignments.put(domain, domain.name().toLowerCase());
        return new SupervisorPlan("question", EnumSet.of(domains[0], domains), assignments, "test");
    }

    private static ExpertFinding finding(ExpertDomain domain) {
        return new ExpertFinding(domain, FindingStatus.SUPPORTED, "finding", List.of("tool:1"), .5, List.of());
    }
}
