package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.customer.CustomerServiceResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceWorkflowTest {

    private final CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
            new MockParkFixture().knowledge(),
            Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
            () -> "cs-demo-001");

    @Test
    void answersParkingQuestionFromMockKnowledgeWithoutHumanTransfer() {
        CustomerServiceResult result = workflow.handle("访客停车怎么收费？");

        assertThat(result.intent()).isEqualTo("PARKING");
        assertThat(result.knowledgeSources()).contains("Visitor parking guide");
        assertThat(result.needsHuman()).isFalse();
        assertThat(result.ticket()).isNull();
    }

    @Test
    void repairRequestCreatesSafeHumanServiceTicket() {
        CustomerServiceResult result = workflow.handle("A1 洗手间漏水，需要报修");

        assertThat(result.intent()).isEqualTo("REPAIR");
        assertThat(result.needsHuman()).isTrue();
        assertThat(result.ticket().id()).isEqualTo("CS-0001");
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(result.ticket().safeSummary()).doesNotContain("A1", "洗手间");
    }

    @Test
    void unknownQuestionsTransferToHumanInsteadOfUsingBroadCustomerServiceKnowledge() {
        CustomerServiceResult result = workflow.handle("园区食堂几点营业？");

        assertThat(result.intent()).isEqualTo("GENERAL");
        assertThat(result.needsHuman()).isTrue();
        assertThat(result.ticket()).isNotNull();
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalSessionAndTicket() {
        CustomerServiceResult first = workflow.handle("A1 洗手间漏水，需要报修", "request-1");
        CustomerServiceResult retry = workflow.handle("A1 洗手间漏水，需要报修", "request-1");

        assertThat(retry).isEqualTo(first);
        assertThat(retry.ticket().id()).isEqualTo(first.ticket().id());
    }

    @Test
    void agentCanMoveTicketThroughTheSupportedLifecycle() {
        CustomerServiceResult created = workflow.handle("A1 洗手间漏水，需要报修");

        CustomerServiceResult assigned = workflow.updateTicket(created.ticket().id(), "ASSIGNED");
        CustomerServiceResult inProgress = workflow.updateTicket(created.ticket().id(), "IN_PROGRESS");
        CustomerServiceResult resolved = workflow.updateTicket(created.ticket().id(), "RESOLVED");
        CustomerServiceResult closed = workflow.updateTicket(created.ticket().id(), "CLOSED");

        assertThat(assigned.ticket().status()).isEqualTo("ASSIGNED");
        assertThat(inProgress.ticket().status()).isEqualTo("IN_PROGRESS");
        assertThat(resolved.ticket().status()).isEqualTo("RESOLVED");
        assertThat(closed.ticket().status()).isEqualTo("CLOSED");
        assertThat(workflow.tickets()).extracting(result -> result.ticket().id()).contains(created.ticket().id());
    }

    @Test
    void ticketCannotSkipLifecycleStates() {
        CustomerServiceResult created = workflow.handle("A1 洗手间漏水，需要报修");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflow.updateTicket(created.ticket().id(), "RESOLVED"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void concurrentRetriesCreateOnlyOneTicket() throws Exception {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        CustomerServiceWorkflow concurrent = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-" + ids.incrementAndGet());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Callable<CustomerServiceResult>> calls = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> (java.util.concurrent.Callable<CustomerServiceResult>)
                            () -> concurrent.handle("A1 洗手间漏水，需要报修", "concurrent-request"))
                    .toList();
            java.util.List<CustomerServiceResult> results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try { return future.get(); }
                        catch (Exception exception) { throw new AssertionError(exception); }
                    }).toList();

            assertThat(results).extracting(CustomerServiceResult::sessionId).containsOnly(results.get(0).sessionId());
            assertThat(results).extracting(result -> result.ticket().id()).containsOnly("CS-0001");
            assertThat(ids).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void expiredSessionsAndIdempotencyEntriesAreRemoved() {
        java.time.Clock mutableClock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        CustomerServiceWorkflow expiring = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(), mutableClock, () -> "cs-expiring", 10, Duration.ofMinutes(5));
        expiring.handle("访客停车怎么收费？", "expiring-request");
        ((MutableClock) mutableClock).instant = Instant.parse("2026-08-23T02:06:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> expiring.get("cs-expiring"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void sessionCapacityEvictsTheOldestSession() {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        CustomerServiceWorkflow bounded = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-" + ids.incrementAndGet(), 2, Duration.ofHours(1));

        bounded.handle("停车怎么收费？");
        bounded.handle("访客怎么预约？");
        bounded.handle("公共区域能耗如何查询？");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bounded.get("cs-1"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(bounded.get("cs-2")).isNotNull();
        assertThat(bounded.get("cs-3")).isNotNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

}
