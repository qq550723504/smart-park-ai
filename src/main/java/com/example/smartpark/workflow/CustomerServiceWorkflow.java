package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.InMemoryCustomerSessionStore;
import com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapter;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.port.customer.CustomerTicketPort;
import com.example.smartpark.port.knowledge.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgePort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class CustomerServiceWorkflow {

    public static final double DEFAULT_MINIMUM_KNOWLEDGE_SCORE = 0.70;

    private final KnowledgePort knowledgePort;
    private final CustomerSessionStore sessionStore;
    private final CustomerTicketPort ticketPort;
    private final Clock clock;
    private final Supplier<String> sessionIds;
    private final double minimumKnowledgeScore;

    public CustomerServiceWorkflow(KnowledgePort knowledgePort) {
        this(knowledgePort, new InMemoryCustomerSessionStore(), new InMemoryCustomerTicketAdapter(),
                Clock.systemUTC(), () -> "cs-" + UUID.randomUUID(), DEFAULT_MINIMUM_KNOWLEDGE_SCORE);
    }

    CustomerServiceWorkflow(KnowledgePort knowledgePort, Clock clock, Supplier<String> sessionIds) {
        this(knowledgePort, clock, sessionIds, 10_000, Duration.ofHours(24));
    }

    CustomerServiceWorkflow(KnowledgePort knowledgePort, Clock clock, Supplier<String> sessionIds,
                            int maxSessions, Duration sessionTtl) {
        this(knowledgePort, new InMemoryCustomerSessionStore(clock, maxSessions, sessionTtl),
                new InMemoryCustomerTicketAdapter(), clock, sessionIds, DEFAULT_MINIMUM_KNOWLEDGE_SCORE);
    }

    public CustomerServiceWorkflow(KnowledgePort knowledgePort, CustomerSessionStore sessionStore,
                                   CustomerTicketPort ticketPort, Clock clock, Supplier<String> sessionIds) {
        this(knowledgePort, sessionStore, ticketPort, clock, sessionIds, DEFAULT_MINIMUM_KNOWLEDGE_SCORE);
    }

    public CustomerServiceWorkflow(KnowledgePort knowledgePort, CustomerSessionStore sessionStore,
                                   CustomerTicketPort ticketPort, Clock clock, Supplier<String> sessionIds,
                                   double minimumKnowledgeScore) {
        this.knowledgePort = Objects.requireNonNull(knowledgePort, "knowledgePort");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.ticketPort = Objects.requireNonNull(ticketPort, "ticketPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
        if (!Double.isFinite(minimumKnowledgeScore) || minimumKnowledgeScore < 0 || minimumKnowledgeScore > 1) {
            throw new IllegalArgumentException("minimumKnowledgeScore must be between 0 and 1");
        }
        this.minimumKnowledgeScore = minimumKnowledgeScore;
    }

    public CustomerServiceResult handle(String question) {
        return handle(question, null);
    }

    public synchronized CustomerServiceResult handle(String question, String idempotencyKey) {
        String normalizedQuestion = requireQuestion(question);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        CustomerSessionStore.IdempotencyScope scope = new CustomerSessionStore.IdempotencyScope(
                CustomerSessionStore.IdempotencyOperation.HANDLE, null);
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        if (normalizedKey != null) {
            CustomerSessionStore.IdempotencyRecord existing = sessionStore.findIdempotency(normalizedKey, now).orElse(null);
            if (existing != null) {
                return replay(existing, scope, normalizedQuestion);
            }
        }

        String sessionId = sessionIds.get();
        Intent intent = classify(normalizedQuestion);
        RetrievalOutcome retrieval = searchKnowledge(intent);
        List<KnowledgeMatch> documents = retrieval.documents();
        boolean needsHuman = intent == Intent.REPAIR || intent == Intent.GENERAL || documents.isEmpty();
        CustomerTicket ticket = needsHuman ? createTicket(sessionId, intent) : null;
        CustomerServiceResult result = new CustomerServiceResult(
                sessionId,
                intent.name(),
                answer(intent, documents, needsHuman, ticket),
                documents.stream().map(KnowledgeMatch::title).toList(),
                needsHuman,
                ticket,
                resultReason(retrieval, needsHuman),
                documents.stream().map(KnowledgeMatch::citationId).distinct().toList());
        sessionStore.create(sessionId, result,
                List.of(
                        new CustomerConversation.Message("USER", normalizedQuestion, now),
                        new CustomerConversation.Message("ASSISTANT", result.answer(), now)),
                List.of(new CustomerConversation.RetrievalTrace(
                        intent.query, documents.stream().map(KnowledgeMatch::citationId).toList(), now)), now);
        retireEvictedSessions(now);

        if (normalizedKey != null) {
            sessionStore.rememberIdempotency(normalizedKey, scope, normalizedQuestion, result, now);
        }
        return result;
    }

    public synchronized CustomerServiceResult reply(String sessionId, String question, String idempotencyKey) {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        CustomerSessionStore.SessionSnapshot current = requiredSnapshot(sessionId, now);
        String normalizedQuestion = requireQuestion(question);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        CustomerSessionStore.IdempotencyScope scope = new CustomerSessionStore.IdempotencyScope(
                CustomerSessionStore.IdempotencyOperation.REPLY, sessionId);
        CustomerSessionStore.IdempotencyRecord existing = normalizedKey == null
                ? null : sessionStore.findIdempotency(normalizedKey, now).orElse(null);
        if (existing != null) {
            return replay(existing, scope, normalizedQuestion);
        }
        if (current.result().needsHuman()) {
            throw new IllegalStateException("Customer service session is handled by a human agent");
        }
        Intent classified = classify(normalizedQuestion);
        Intent intent = classified == Intent.GENERAL ? Intent.valueOf(current.result().intent()) : classified;
        RetrievalOutcome retrieval = searchKnowledge(intent);
        List<KnowledgeMatch> documents = retrieval.documents();
        boolean needsHuman = intent == Intent.REPAIR || documents.isEmpty();
        CustomerTicket ticket = needsHuman ? createTicket(sessionId, intent) : null;
        CustomerServiceResult result = new CustomerServiceResult(
                sessionId, intent.name(), answer(intent, documents, needsHuman, ticket),
                documents.stream().map(KnowledgeMatch::title).toList(), needsHuman, ticket,
                resultReason(retrieval, needsHuman),
                documents.stream().map(KnowledgeMatch::citationId).distinct().toList());
        List<CustomerConversation.Message> messages = new java.util.ArrayList<>(current.messages());
        messages.add(new CustomerConversation.Message("USER", normalizedQuestion, now));
        messages.add(new CustomerConversation.Message("ASSISTANT", result.answer(), now));
        List<CustomerConversation.RetrievalTrace> retrievals = new java.util.ArrayList<>(current.retrievals());
        retrievals.add(new CustomerConversation.RetrievalTrace(
                intent.query, documents.stream().map(KnowledgeMatch::citationId).toList(), now));
        sessionStore.update(new CustomerSessionStore.SessionSnapshot(
                sessionId, result, current.createdAt(), messages, retrievals));
        if (normalizedKey != null) {
            sessionStore.rememberIdempotency(normalizedKey, scope, normalizedQuestion, result, now);
        }
        return result;
    }

    public CustomerConversation conversation(String sessionId) {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        CustomerSessionStore.SessionSnapshot entry = requiredSnapshot(sessionId, now);
        return new CustomerConversation(sessionId, entry.messages(), entry.retrievals(), entry.result().needsHuman());
    }

    public int sessionCount() {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        return sessionStore.count(now);
    }

    public synchronized List<CustomerServiceResult> tickets() {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        List<CustomerServiceResult> results = new java.util.ArrayList<>();
        for (CustomerTicket ticket : ticketPort.list()) {
            CustomerSessionStore.SessionSnapshot snapshot = sessionStore.find(ticket.sessionId(), now).orElse(null);
            if (snapshot == null) {
                ticketPort.deleteBySessionId(ticket.sessionId());
                continue;
            }
            results.add(withTicket(snapshot.result(), ticket));
        }
        return List.copyOf(results);
    }

    public synchronized CustomerServiceResult updateTicket(String ticketId, String status) {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        CustomerTicketStatus nextStatus = CustomerTicketStatus.valueOf(status);
        CustomerTicket currentTicket = ticketPort.list().stream()
                .filter(ticket -> ticket.id().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown customer service ticket: " + ticketId));
        CustomerSessionStore.SessionSnapshot match = sessionStore.find(currentTicket.sessionId(), now).orElse(null);
        if (match == null) {
            ticketPort.deleteBySessionId(currentTicket.sessionId());
            throw new NoSuchElementException("Unknown customer service ticket: " + ticketId);
        }
        currentTicket.transitionTo(nextStatus);
        CustomerTicket updatedTicket = ticketPort.update(ticketId, nextStatus);
        CustomerServiceResult updated = withTicket(match.result(), updatedTicket);
        sessionStore.update(new CustomerSessionStore.SessionSnapshot(
                match.sessionId(), updated, match.createdAt(), match.messages(), match.retrievals()));
        return updated;
    }
    public CustomerServiceResult get(String sessionId) {
        Instant now = Instant.now(clock);
        retireEvictedSessions(now);
        return requiredSnapshot(sessionId, now).result();
    }

    private CustomerSessionStore.SessionSnapshot requiredSnapshot(String sessionId, Instant now) {
        return sessionStore.find(sessionId, now)
                .orElseThrow(() -> new NoSuchElementException("Unknown customer service session: " + sessionId));
    }

    private RetrievalOutcome searchKnowledge(Intent intent) {
        if (intent == Intent.GENERAL) return RetrievalOutcome.noEvidence();
        try {
            // Keep retrieval to one port call so a transient provider failure has one safe mapping.
            List<KnowledgeMatch> documents = knowledgePort.rankedSearch(intent.query).stream()
                    // A zero score means that the adapter did not establish relevance.
                    // It must never become evidence merely because the configured threshold is zero.
                    .filter(match -> match.score() > 0.0 && match.score() >= minimumKnowledgeScore)
                    .toList();
            return documents.isEmpty() ? RetrievalOutcome.noEvidence() : RetrievalOutcome.supported(documents);
        } catch (RuntimeException exception) {
            // Embedding and vector-store failures are operational failures, not empty evidence.
            return RetrievalOutcome.unavailable();
        }
    }

    private record RetrievalOutcome(List<KnowledgeMatch> documents, String reason) {
        private RetrievalOutcome {
            documents = List.copyOf(documents);
        }

        static RetrievalOutcome supported(List<KnowledgeMatch> documents) {
            return new RetrievalOutcome(documents, "SUPPORTED");
        }

        static RetrievalOutcome noEvidence() {
            return new RetrievalOutcome(List.of(), "INSUFFICIENT_EVIDENCE");
        }

        static RetrievalOutcome unavailable() {
            return new RetrievalOutcome(List.of(), "RETRIEVAL_UNAVAILABLE");
        }
    }

    private static String resultReason(RetrievalOutcome retrieval, boolean needsHuman) {
        if ("RETRIEVAL_UNAVAILABLE".equals(retrieval.reason())) return retrieval.reason();
        if (!needsHuman) return "SUPPORTED";
        return retrieval.documents().isEmpty() ? "INSUFFICIENT_EVIDENCE" : "POLICY_LIMIT";
    }

    private CustomerTicket createTicket(String sessionId, Intent intent) {
        return ticketPort.create(sessionId, intent.name(), intent.safeTicketSummary, Instant.now(clock));
    }

    private void retireEvictedSessions(Instant now) {
        sessionStore.evict(now).forEach(ticketPort::deleteBySessionId);
    }

    private static CustomerServiceResult withTicket(CustomerServiceResult current, CustomerTicket ticket) {
        return new CustomerServiceResult(
                current.sessionId(), current.intent(), current.answer(), current.knowledgeSources(), true, ticket,
                current.reason(), current.citationIds());
    }

    private static CustomerServiceResult replay(CustomerSessionStore.IdempotencyRecord existing,
                                                CustomerSessionStore.IdempotencyScope expectedScope,
                                                String normalizedQuestion) {
        if (!existing.question().equals(normalizedQuestion)) {
            throw new IllegalStateException("Idempotency key was already used for another question");
        }
        if (!existing.scope().equals(expectedScope)) {
            throw new IllegalStateException("Idempotency key was already used for another operation or session");
        }
        return existing.result();
    }

    private static Intent classify(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        if (containsAny(text, "报修", "坏了", "漏水", "故障", "维修")) return Intent.REPAIR;
        if (containsAny(text, "停车", "停车场", "车位", "停车费")) return Intent.PARKING;
        if (containsAny(text, "访客", "来访", "通行", "门禁", "预约")) return Intent.VISITOR;
        if (containsAny(text, "能耗", "用电", "电费", "节能")) return Intent.ENERGY;
        return Intent.GENERAL;
    }

    private static String answer(Intent intent, List<KnowledgeMatch> documents, boolean needsHuman, CustomerTicket ticket) {
        if (needsHuman) {
            if (intent == Intent.REPAIR) {
                return "已记录设施报修并创建客服工单 " + ticket.id() + "。客服将核实位置和设备信息后安排处理。";
            }
            return "当前知识库没有足够信息，已创建人工客服工单 " + ticket.id() + "，请等待园区客服处理。";
        }
        return switch (intent) {
            case PARKING -> "访客车辆可在园区入口完成登记后进入访客停车区，具体收费与开放时段请以园区停车指引为准。";
            case VISITOR -> "访客需由园区联系人提前预约，到访时按门岗指引核验预约信息；客服不会在对话中索取身份证原始资料。";
            case ENERGY -> "可以查询公共区域的能耗趋势和节能建议；涉及租户账单或设备控制时需要人工核验权限。";
            default -> throw new IllegalStateException("Unsupported answered intent: " + intent);
        };
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim();
        if (normalized.length() > 200) throw new CustomerServiceValidationException("Idempotency-Key must not exceed 200 characters");
        return normalized;
    }

    private static String requireQuestion(String question) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        String normalized = question.trim();
        if (normalized.length() > 500) throw new CustomerServiceValidationException("question must not exceed 500 characters");
        return normalized;
    }

    private enum Intent {
        PARKING("parking", "停车咨询需人工跟进"),
        VISITOR("visitor", "访客通行咨询需人工跟进"),
        ENERGY("energy", "能耗咨询需人工跟进"),
        REPAIR("repair", "园区设施报修，待客服核实位置和设备"),
        GENERAL("", "一般园区咨询，知识库暂无答案");

        private final String query;
        private final String safeTicketSummary;

        Intent(String query, String safeTicketSummary) {
            this.query = query;
            this.safeTicketSummary = safeTicketSummary;
        }
    }
}
