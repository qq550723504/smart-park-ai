package com.example.smartpark.workflow;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.knowledge.KnowledgePort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class CustomerServiceWorkflow {

    private final KnowledgePort knowledgePort;
    private final Clock clock;
    private final Supplier<String> sessionIds;
    private final AtomicInteger ticketSequence = new AtomicInteger();
    private final AtomicInteger sessionSequence = new AtomicInteger();
    private final int maxSessions;
    private final Duration sessionTtl;
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IdempotentRequest> idempotentRequests = new ConcurrentHashMap<>();

    public CustomerServiceWorkflow(KnowledgePort knowledgePort) {
        this(knowledgePort, Clock.systemUTC(), () -> "cs-" + UUID.randomUUID(), 10_000, Duration.ofHours(24));
    }

    CustomerServiceWorkflow(KnowledgePort knowledgePort, Clock clock, Supplier<String> sessionIds) {
        this(knowledgePort, clock, sessionIds, 10_000, Duration.ofHours(24));
    }

    CustomerServiceWorkflow(KnowledgePort knowledgePort, Clock clock, Supplier<String> sessionIds,
                            int maxSessions, Duration sessionTtl) {
        this.knowledgePort = Objects.requireNonNull(knowledgePort, "knowledgePort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
        if (maxSessions < 1) throw new IllegalArgumentException("maxSessions must be positive");
        this.maxSessions = maxSessions;
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isZero() || sessionTtl.isNegative()) throw new IllegalArgumentException("sessionTtl must be positive");
    }

    public CustomerServiceResult handle(String question) {
        return handle(question, null);
    }

    public synchronized CustomerServiceResult handle(String question, String idempotencyKey) {
        String normalizedQuestion = requireQuestion(question);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        Instant now = Instant.now(clock);
        if (normalizedKey != null) {
            IdempotentRequest existing = idempotentRequests.get(normalizedKey);
            if (existing != null && !isExpired(existing.createdAt(), now)) {
                if (!existing.question().equals(normalizedQuestion)) {
                    throw new IllegalStateException("Idempotency key was already used for another question");
                }
                return existing.result();
            }
            if (existing != null) idempotentRequests.remove(normalizedKey, existing);
        }

        String sessionId = sessionIds.get();
        Intent intent = classify(normalizedQuestion);
        List<KnowledgeDocument> documents = intent == Intent.GENERAL ? List.of() : knowledgePort.search(intent.query);
        boolean needsHuman = intent == Intent.REPAIR || intent == Intent.GENERAL || documents.isEmpty();
        CustomerTicket ticket = needsHuman ? createTicket(sessionId, intent) : null;
        CustomerServiceResult result = new CustomerServiceResult(
                sessionId,
                intent.name(),
                answer(intent, documents, needsHuman, ticket),
                documents.stream().map(KnowledgeDocument::title).toList(),
                needsHuman,
                ticket);
        sessions.put(sessionId, new SessionEntry(
                result, now, sessionSequence.incrementAndGet(),
                List.of(
                        new CustomerConversation.Message("USER", normalizedQuestion, now),
                        new CustomerConversation.Message("ASSISTANT", result.answer(), now)),
                List.of(new CustomerConversation.RetrievalTrace(
                        intent.query, documents.stream().map(KnowledgeDocument::id).toList(), now))));
        evictExpiredAndOverCapacity(now);

        if (normalizedKey != null) {
            IdempotentRequest request = new IdempotentRequest(normalizedQuestion, result, now);
            IdempotentRequest prior = idempotentRequests.putIfAbsent(normalizedKey, request);
            if (prior != null) {
                if (!prior.question().equals(normalizedQuestion)) {
                    throw new IllegalStateException("Idempotency key was already used for another question");
                }
                return prior.result();
            }
        }
        return result;
    }

    public synchronized CustomerServiceResult reply(String sessionId, String question, String idempotencyKey) {
        SessionEntry currentEntry = requiredEntry(sessionId);
        if (currentEntry.result().needsHuman()) {
            throw new IllegalStateException("Customer service session is handled by a human agent");
        }
        String normalizedQuestion = requireQuestion(question);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        IdempotentRequest existing = normalizedKey == null ? null : idempotentRequests.get(normalizedKey);
        if (existing != null) {
            if (!existing.question().equals(normalizedQuestion)) {
                throw new IllegalStateException("Idempotency key was already used for another question");
            }
            return existing.result();
        }
        Intent classified = classify(normalizedQuestion);
        Intent intent = classified == Intent.GENERAL ? Intent.valueOf(currentEntry.result().intent()) : classified;
        Instant now = Instant.now(clock);
        List<KnowledgeDocument> documents = knowledgePort.search(intent.query);
        boolean needsHuman = documents.isEmpty();
        CustomerTicket ticket = needsHuman ? createTicket(sessionId, intent) : null;
        CustomerServiceResult result = new CustomerServiceResult(
                sessionId, intent.name(), answer(intent, documents, needsHuman, ticket),
                documents.stream().map(KnowledgeDocument::title).toList(), needsHuman, ticket);
        List<CustomerConversation.Message> messages = new java.util.ArrayList<>(currentEntry.messages());
        messages.add(new CustomerConversation.Message("USER", normalizedQuestion, now));
        messages.add(new CustomerConversation.Message("ASSISTANT", result.answer(), now));
        List<CustomerConversation.RetrievalTrace> retrievals = new java.util.ArrayList<>(currentEntry.retrievals());
        retrievals.add(new CustomerConversation.RetrievalTrace(
                intent.query, documents.stream().map(KnowledgeDocument::id).toList(), now));
        SessionEntry updated = new SessionEntry(result, currentEntry.createdAt(), currentEntry.sequence(), messages, retrievals);
        sessions.put(sessionId, updated);
        if (normalizedKey != null) idempotentRequests.put(normalizedKey, new IdempotentRequest(normalizedQuestion, result, now));
        return result;
    }

    public CustomerConversation conversation(String sessionId) {
        SessionEntry entry = requiredEntry(sessionId);
        return new CustomerConversation(sessionId, entry.messages(), entry.retrievals(), entry.result().needsHuman());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public synchronized List<CustomerServiceResult> tickets() {
        return sessions.values().stream()
                .map(SessionEntry::result)
                .filter(result -> result.ticket() != null)
                .sorted(java.util.Comparator.comparing(result -> result.ticket().createdAt()))
                .toList();
    }

    public synchronized CustomerServiceResult updateTicket(String ticketId, String status) {
        CustomerTicketStatus nextStatus = CustomerTicketStatus.valueOf(status);
        Map.Entry<String, SessionEntry> match = sessions.entrySet().stream()
                .filter(entry -> entry.getValue().result().ticket() != null)
                .filter(entry -> entry.getValue().result().ticket().id().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown customer service ticket: " + ticketId));
        CustomerServiceResult current = match.getValue().result();
        CustomerTicket updatedTicket = current.ticket().transitionTo(nextStatus);
        CustomerServiceResult updated = new CustomerServiceResult(
                current.sessionId(), current.intent(), current.answer(), current.knowledgeSources(), true, updatedTicket);
        sessions.put(match.getKey(), new SessionEntry(
                updated, match.getValue().createdAt(), match.getValue().sequence(),
                match.getValue().messages(), match.getValue().retrievals()));
        idempotentRequests.replaceAll((key, request) -> request.result().sessionId().equals(updated.sessionId())
                ? new IdempotentRequest(request.question(), updated, request.createdAt()) : request);
        return updated;
    }
    public CustomerServiceResult get(String sessionId) {
        return requiredEntry(sessionId).result();
    }

    private SessionEntry requiredEntry(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null || isExpired(entry.createdAt(), Instant.now(clock))) {
            if (entry != null) sessions.remove(sessionId, entry);
            throw new NoSuchElementException("Unknown customer service session: " + sessionId);
        }
        return entry;
    }

    private CustomerTicket createTicket(String sessionId, Intent intent) {
        return new CustomerTicket(
                String.format("CS-%04d", ticketSequence.incrementAndGet()), sessionId, intent.name(), "WAITING_AGENT",
                intent.safeTicketSummary, Instant.now(clock));
    }

    private static Intent classify(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        if (containsAny(text, "报修", "坏了", "漏水", "故障", "维修")) return Intent.REPAIR;
        if (containsAny(text, "停车", "停车场", "车位", "停车费")) return Intent.PARKING;
        if (containsAny(text, "访客", "来访", "通行", "门禁", "预约")) return Intent.VISITOR;
        if (containsAny(text, "能耗", "用电", "电费", "节能")) return Intent.ENERGY;
        return Intent.GENERAL;
    }

    private static String answer(Intent intent, List<KnowledgeDocument> documents, boolean needsHuman, CustomerTicket ticket) {
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

    private void evictExpiredAndOverCapacity(Instant now) {
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue().createdAt(), now));
        idempotentRequests.entrySet().removeIf(entry -> isExpired(entry.getValue().createdAt(), now));
        while (sessions.size() > maxSessions) {
            Map.Entry<String, SessionEntry> oldest = sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(java.util.Comparator.comparing(SessionEntry::sequence)))
                    .orElse(null);
            if (oldest == null || !sessions.remove(oldest.getKey(), oldest.getValue())) break;
            idempotentRequests.entrySet().removeIf(entry -> entry.getValue().result().sessionId().equals(oldest.getKey()));
        }
    }

    private boolean isExpired(Instant createdAt, Instant now) {
        return !createdAt.plus(sessionTtl).isAfter(now);
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

    private record SessionEntry(
            CustomerServiceResult result,
            Instant createdAt,
            int sequence,
            List<CustomerConversation.Message> messages,
            List<CustomerConversation.RetrievalTrace> retrievals) {
        private SessionEntry {
            messages = List.copyOf(messages);
            retrievals = List.copyOf(retrievals);
        }
    }
    private record IdempotentRequest(String question, CustomerServiceResult result, Instant createdAt) { }

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
