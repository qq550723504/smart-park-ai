package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.adapter.mock.MockCustomerAnswerAdapter;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerAnswerPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.customer.CustomerTicketPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceWorkflowConcurrencyTest {
    @Test
    void unrelatedRequestsProgressWhileAnotherProviderCallIsBlocked() throws Exception {
        CountDownLatch firstProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstProvider = new CountDownLatch(1);
        KnowledgeDocument parking = document("KD-PARKING-001", "Parking", "parking");
        KnowledgeDocument visitor = document("KD-VISITOR-001", "Visitor", "visitor");
        KnowledgePort knowledge = new KnowledgePort() {
            @Override
            public List<com.example.smartpark.model.common.KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                return List.of();
            }

            @Override
            public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                if (query.equals("parking")) {
                    firstProviderEntered.countDown();
                    try {
                        if (!releaseFirstProvider.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting for test release");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while blocking provider", interrupted);
                    }
                    return List.of(new KnowledgeMatch(parking, .9));
                }
                return List.of(new KnowledgeMatch(visitor, .9));
            }
        };
        CustomerAnswerPort answer = (question, intent, evidence) ->
                new CustomerAnswer("safe answer", false, CustomerAnswer.Reason.SUPPORTED,
                        List.of(evidence.get(0).documentId()));
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                knowledge, new InMemoryCustomerSessionStore(clock, 100, Duration.ofHours(24)),
                new InMemoryCustomerTicketAdapter(), answer, clock, new AtomicInteger()::toString);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<CustomerServiceResult> first = executor.submit(() -> workflow.handle("访客停车怎么收费？"));
        assertThat(firstProviderEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Future<CustomerServiceResult> second = executor.submit(() -> workflow.handle("访客如何预约进入园区？"));
        try {
            assertThat(second.get(1, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseFirstProvider.countDown();
            first.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void serializesRepliesForOneSessionWithoutBlockingUnrelatedHandles() throws Exception {
        CountDownLatch firstReplyEntered = new CountDownLatch(1);
        CountDownLatch secondReplyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstReply = new CountDownLatch(1);
        AtomicInteger searches = new AtomicInteger();
        KnowledgeDocument parking = document("KD-PARKING-001", "Parking", "parking");
        KnowledgeDocument visitor = document("KD-VISITOR-001", "Visitor", "visitor");
        KnowledgeDocument energy = document("KD-ENERGY-001", "Energy", "energy");
        KnowledgePort knowledge = new KnowledgePort() {
            @Override
            public List<com.example.smartpark.model.common.KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                return List.of();
            }

            @Override
            public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                int call = searches.incrementAndGet();
                if (call == 2) {
                    firstReplyEntered.countDown();
                    await(releaseFirstReply);
                } else if (call >= 3) {
                    secondReplyEntered.countDown();
                }
                KnowledgeDocument match = query.equals("parking") ? parking
                        : query.equals("visitor") ? visitor : energy;
                return List.of(new KnowledgeMatch(match, .9));
            }
        };
        CustomerAnswerPort answer = (question, intent, evidence) ->
                new CustomerAnswer("safe answer", false, CustomerAnswer.Reason.SUPPORTED,
                        List.of(evidence.get(0).documentId()));
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                knowledge, new InMemoryCustomerSessionStore(clock, 100, Duration.ofHours(24)),
                new InMemoryCustomerTicketAdapter(), answer, clock, new AtomicInteger()::toString);
        CustomerServiceResult session = workflow.handle("访客停车怎么收费？");
        assertThat(searches).hasValue(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<CustomerServiceResult> first = executor.submit(
                () -> workflow.reply(session.sessionId(), "访客如何预约进入园区？", "reply-1"));
        boolean firstEntered = firstReplyEntered.await(5, TimeUnit.SECONDS);
        if (!firstEntered) {
            releaseFirstReply.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(firstEntered).isTrue();
        Future<CustomerServiceResult> second = executor.submit(
                () -> workflow.reply(session.sessionId(), "公共区域能耗如何查询？", "reply-2"));
        try {
            assertThat(secondReplyEntered.await(1, TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseFirstReply.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
        assertThat(workflow.conversation(session.sessionId()).messages()).hasSize(6);
    }

    @Test
    void concurrentRetriesShareOneInFlightIdempotentExecution() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger searches = new AtomicInteger();
        KnowledgeDocument parking = document("KD-PARKING-001", "Parking", "parking");
        KnowledgePort knowledge = new KnowledgePort() {
            @Override
            public List<com.example.smartpark.model.common.KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                return List.of();
            }

            @Override
            public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                searches.incrementAndGet();
                providerEntered.countDown();
                await(releaseProvider);
                return List.of(new KnowledgeMatch(parking, .9));
            }
        };
        CustomerAnswerPort answer = (question, intent, evidence) ->
                new CustomerAnswer("safe answer", false, CustomerAnswer.Reason.SUPPORTED,
                        List.of(evidence.get(0).documentId()));
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                knowledge, new InMemoryCustomerSessionStore(clock, 100, Duration.ofHours(24)),
                new InMemoryCustomerTicketAdapter(), answer, clock, new AtomicInteger()::toString);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CustomerServiceResult> first = executor.submit(
                    () -> workflow.handle("访客停车怎么收费？", "same-request"));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<CustomerServiceResult> retry = executor.submit(
                    () -> workflow.handle("访客停车怎么收费？", "same-request"));
            Thread.sleep(250);
            assertThat(retry.isDone()).isFalse();
            assertThat(searches).hasValue(1);

            releaseProvider.countDown();
            CustomerServiceResult firstResult = first.get(5, TimeUnit.SECONDS);
            assertThat(retry.get(5, TimeUnit.SECONDS)).isEqualTo(firstResult);
            assertThat(searches).hasValue(1);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ticketListCannotDeleteAHandOffBeforeItsSessionIsPublished() throws Exception {
        BlockingTicketPort tickets = new BlockingTicketPort();
        KnowledgePort knowledge = (domain, query) -> List.of();
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                knowledge, new InMemoryCustomerSessionStore(clock, 100, Duration.ofHours(24)),
                tickets, new MockCustomerAnswerAdapter(), clock, () -> "cs-atomic");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CustomerServiceResult> request = executor.submit(
                    () -> workflow.handle("A1 洗手间漏水，需要报修？", "repair-atomic"));
            assertThat(tickets.createEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<CustomerServiceResult>> listed = executor.submit(workflow::tickets);
            Thread.sleep(250);
            assertThat(listed.isDone()).isFalse();

            tickets.releaseCreate.countDown();
            CustomerServiceResult result = request.get(5, TimeUnit.SECONDS);
            assertThat(result.needsHuman()).isTrue();
            assertThat(listed.get(5, TimeUnit.SECONDS)).extracting(CustomerServiceResult::sessionId)
                    .containsExactly("cs-atomic");
        } finally {
            tickets.releaseCreate.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while blocking provider", interrupted);
        }
    }

    private static KnowledgeDocument document(String id, String title, String content) {
        return new KnowledgeDocument(id, KnowledgeDomain.CUSTOMER_SERVICE, title, content, List.of("test"), Instant.EPOCH);
    }

    private static final class BlockingTicketPort implements CustomerTicketPort {
        private final InMemoryCustomerTicketAdapter delegate = new InMemoryCustomerTicketAdapter();
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);

        @Override
        public CustomerTicket create(String sessionId, String intent, String safeSummary, Instant createdAt) {
            CustomerTicket ticket = delegate.create(sessionId, intent, safeSummary, createdAt);
            createEntered.countDown();
            try {
                if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for ticket publication release");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while blocking ticket publication", interrupted);
            }
            return ticket;
        }

        @Override public List<CustomerTicket> list() { return delegate.list(); }
        @Override public CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus) { return delegate.update(ticketId, nextStatus); }
        @Override public CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus, Instant updatedAt) { return delegate.update(ticketId, nextStatus, updatedAt); }
        @Override public void deleteBySessionId(String sessionId) { delegate.deleteBySessionId(sessionId); }
    }
}
