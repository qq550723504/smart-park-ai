package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.port.knowledge.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgePort;
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
    void knowledgeBelowConfiguredScoreThresholdTransfersToHuman() {
        CustomerServiceWorkflow thresholded = workflowWithRankedMatch(0.69, 0.70, "cs-low-score");

        CustomerServiceResult result = thresholded.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(result.knowledgeSources()).isEmpty();
        assertThat(result.citationIds()).isEmpty();
        assertThat(thresholded.conversation(result.sessionId()).retrievals()).singleElement()
                .satisfies(trace -> assertThat(trace.documentIds()).isEmpty());
    }

    @Test
    void knowledgeAtConfiguredScoreThresholdRemainsSupported() {
        CustomerServiceWorkflow thresholded = workflowWithRankedMatch(0.70, 0.70, "cs-at-threshold");

        CustomerServiceResult result = thresholded.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isFalse();
        assertThat(result.reason()).isEqualTo("SUPPORTED");
        assertThat(result.citationIds()).containsExactly("parking-low-score");
    }

    @Test
    void zeroScoreMatchIsRejectedEvenWhenMinimumThresholdIsZero() {
        CustomerServiceWorkflow thresholded = workflowWithRankedMatch(0.0, 0.0, "cs-zero-score");

        CustomerServiceResult result = thresholded.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.citationIds()).isEmpty();
    }

    @Test
    void initialKnowledgeSearchFailureTransfersToHumanWithWaitingTicket() {
        String secret = "providerResponse=private-knowledge-body";
        KnowledgePort failingKnowledge = query -> {
            throw new IllegalStateException(secret);
        };
        CustomerServiceWorkflow failing = new CustomerServiceWorkflow(
                failingKnowledge,
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-search-failure");
        CustomerServiceResult result = failing.handle("访客停车怎么收费？");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(result.answer()).doesNotContain(secret, "private-knowledge-body");
        assertThat(failing.conversation(result.sessionId()).retrievals()).singleElement()
                .satisfies(trace -> assertThat(trace.documentIds()).isEmpty());
    }

    @Test
    void followUpKnowledgeSearchFailureTransfersToHumanWithoutPersistingResponseOrException() {
        String secret = "raw knowledge body and exception token";
        KnowledgePort failingKnowledge = new KnowledgePort() {
            private int calls;

            @Override
            public java.util.List<com.example.smartpark.model.common.KnowledgeDocument> search(String query) {
                if (++calls == 1) return new MockParkFixture().knowledge().search(query);
                throw new IllegalStateException(secret);
            }

            @Override
            public java.util.List<KnowledgeMatch> rankedSearch(String query) {
                return search(query).stream()
                        .map(document -> new KnowledgeMatch(document.id(), document.title(), 1.0))
                        .toList();
            }
        };
        CustomerServiceWorkflow failing = new CustomerServiceWorkflow(
                failingKnowledge,
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-follow-up-search-failure");
        CustomerServiceResult first = failing.handle("访客停车怎么收费？");

        CustomerServiceResult result = failing.reply(
                first.sessionId(), "访客如何预约进入园区？", "search-failure-reply");

        assertThat(result.needsHuman()).isTrue();
        assertThat(result.reason()).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(result.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(failing.conversation(result.sessionId()).messages())
                .extracting(CustomerConversation.Message::text)
                .allMatch(content -> !content.contains(secret));
        assertThat(failing.conversation(result.sessionId()).retrievals()).last()
                .satisfies(trace -> {
                    assertThat(trace.documentIds()).isEmpty();
                    assertThat(trace.query()).doesNotContain(secret);
                });
    }

    @Test
    void vectorRetrievalFailureUsesTheSameSafeHandoffMapping() {
        KnowledgePort vectorStoreFailure = query -> {
            throw new RuntimeException("EmbeddingModel timeout: raw provider response");
        };
        CustomerServiceWorkflow failing = new CustomerServiceWorkflow(
                vectorStoreFailure,
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-vector-failure");

        CustomerServiceResult result = failing.handle("访客停车怎么收费？");

        assertThat(result.reason()).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(result.needsHuman()).isTrue();
        assertThat(result.answer()).doesNotContain("EmbeddingModel", "raw provider response");
        assertThat(result.ticket()).isNotNull();
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
    void repairFollowUpCreatesHumanTicketEvenWhenRepairKnowledgeExists() {
        CustomerServiceResult first = workflow.handle("访客停车怎么收费？");

        CustomerServiceResult repair = workflow.reply(
                first.sessionId(), "A1 洗手间漏水，需要报修", "repair-follow-up");

        assertThat(repair.intent()).isEqualTo("REPAIR");
        assertThat(repair.needsHuman()).isTrue();
        assertThat(repair.ticket()).isNotNull();
        assertThat(repair.ticket().status()).isEqualTo("WAITING_AGENT");
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalSessionAndTicket() {
        CustomerServiceResult first = workflow.handle("A1 洗手间漏水，需要报修", "request-1");
        CustomerServiceResult retry = workflow.handle("A1 洗手间漏水，需要报修", "request-1");

        assertThat(retry).isEqualTo(first);
        assertThat(retry.ticket().id()).isEqualTo(first.ticket().id());
    }

    @Test
    void idempotentHandleRetryReturnsItsOriginalResultAfterALaterReply() {
        CustomerServiceResult first = workflow.handle("访客停车怎么收费？", "parking-request");
        CustomerServiceResult reply = workflow.reply(first.sessionId(), "访客如何预约进入园区？", "visitor-request");

        CustomerServiceResult retry = workflow.handle("访客停车怎么收费？", "parking-request");

        assertThat(reply.intent()).isEqualTo("VISITOR");
        assertThat(retry).isEqualTo(first);
    }

    @Test
    void idempotencyKeyCannotBeReusedAcrossHandleAndReply() {
        workflow.handle("访客停车怎么收费？", "shared-operation-key");
        CustomerServiceResult target = workflow.handle("访客如何预约进入园区？");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.reply(
                        target.sessionId(), "访客停车怎么收费？", "shared-operation-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replyIdempotencyKeyCannotBeReusedForAnotherSession() {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        CustomerServiceWorkflow scoped = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-scope-" + ids.incrementAndGet());
        CustomerServiceResult firstSession = scoped.handle("访客停车怎么收费？");
        CustomerServiceResult secondSession = scoped.handle("访客如何预约进入园区？");
        scoped.reply(firstSession.sessionId(), "公共区域能耗如何查询？", "shared-reply-key");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scoped.reply(
                        secondSession.sessionId(), "公共区域能耗如何查询？", "shared-reply-key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void idempotentHandleRetryKeepsOriginalTicketSnapshotAfterTicketUpdate() {
        CustomerServiceResult first = workflow.handle(
                "A1 洗手间漏水，需要报修", "repair-handle-request");
        workflow.updateTicket(first.ticket().id(), "ASSIGNED");

        CustomerServiceResult retry = workflow.handle(
                "A1 洗手间漏水，需要报修", "repair-handle-request");

        assertThat(retry).isEqualTo(first);
        assertThat(retry.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(workflow.get(first.sessionId()).ticket().status()).isEqualTo("ASSIGNED");
    }

    @Test
    void idempotentReplyRetryKeepsOriginalTicketSnapshotAfterTicketUpdate() {
        CustomerServiceResult session = workflow.handle("访客停车怎么收费？");
        CustomerServiceResult firstReply = workflow.reply(
                session.sessionId(), "A1 洗手间漏水，需要报修", "repair-reply-request");
        workflow.updateTicket(firstReply.ticket().id(), "ASSIGNED");

        CustomerServiceResult retry = workflow.reply(
                session.sessionId(), "A1 洗手间漏水，需要报修", "repair-reply-request");

        assertThat(retry).isEqualTo(firstReply);
        assertThat(retry.ticket().status()).isEqualTo("WAITING_AGENT");
        assertThat(workflow.get(session.sessionId()).ticket().status()).isEqualTo("ASSIGNED");
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
                new MockParkFixture().knowledge(),
                new InMemoryCustomerSessionStore(mutableClock, 10, Duration.ofMinutes(5)),
                new InMemoryCustomerTicketAdapter(), mutableClock, () -> "cs-expiring");
        expiring.handle("访客停车怎么收费？", "expiring-request");
        ((MutableClock) mutableClock).instant = Instant.parse("2026-08-23T02:06:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> expiring.get("cs-expiring"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void expiredSessionPreventsTicketTransitionFromPersisting() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerTicketAdapter tickets = new InMemoryCustomerTicketAdapter();
        CustomerServiceWorkflow expiring = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                new InMemoryCustomerSessionStore(mutableClock, 10, Duration.ofMinutes(5)),
                tickets, mutableClock, () -> "cs-expiring-ticket");
        CustomerServiceResult created = expiring.handle("A1 洗手间漏水，需要报修");
        mutableClock.instant = Instant.parse("2026-08-23T02:06:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> expiring.updateTicket(created.ticket().id(), "ASSIGNED"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Unknown customer service ticket: " + created.ticket().id());

        assertThat(tickets.list()).isEmpty();
    }

    @Test
    void sessionCapacityEvictsTheOldestSession() {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC);
        CustomerServiceWorkflow bounded = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                new InMemoryCustomerSessionStore(clock, 2, Duration.ofHours(1)),
                new InMemoryCustomerTicketAdapter(), clock, () -> "cs-" + ids.incrementAndGet());

        bounded.handle("停车怎么收费？");
        bounded.handle("访客怎么预约？");
        bounded.handle("公共区域能耗如何查询？");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bounded.get("cs-1"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(bounded.get("cs-2")).isNotNull();
        assertThat(bounded.get("cs-3")).isNotNull();
    }

    @Test
    void capacityEvictionRemovesTicketFromApiAndCanonicalPortWithoutPartialUpdate() {
        java.util.concurrent.atomic.AtomicInteger ids = new java.util.concurrent.atomic.AtomicInteger();
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC);
        InMemoryCustomerTicketAdapter tickets = new InMemoryCustomerTicketAdapter();
        CustomerServiceWorkflow bounded = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(),
                new InMemoryCustomerSessionStore(clock, 1, Duration.ofHours(1)),
                tickets, clock, () -> "cs-ticket-" + ids.incrementAndGet());
        CustomerServiceResult evicted = bounded.handle("A1 洗手间漏水，需要报修");
        CustomerServiceResult retained = bounded.handle("B2 空调坏了，需要维修");

        assertThat(bounded.tickets()).extracting(result -> result.ticket().id())
                .containsExactly(retained.ticket().id());
        assertThat(tickets.list()).extracting(ticket -> ticket.id())
                .containsExactly(retained.ticket().id());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> bounded.updateTicket(evicted.ticket().id(), "ASSIGNED"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Unknown customer service ticket: " + evicted.ticket().id());
        assertThat(tickets.list()).containsExactly(retained.ticket());
        assertThat(bounded.get(retained.sessionId())).isEqualTo(retained);
    }

    private static CustomerServiceWorkflow workflowWithRankedMatch(
            double score, double minimumScore, String sessionId) {
        KnowledgePort knowledge = new KnowledgePort() {
            @Override
            public java.util.List<KnowledgeDocument> search(String query) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<KnowledgeMatch> rankedSearch(String query) {
                return java.util.List.of(new KnowledgeMatch("parking-low-score", "Weak parking match", score));
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC);
        return new CustomerServiceWorkflow(
                knowledge, new InMemoryCustomerSessionStore(clock, 10, Duration.ofHours(1)),
                new InMemoryCustomerTicketAdapter(), clock, () -> sessionId, minimumScore);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

}
