package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.workflow.CustomerConversation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryCustomerSessionStore implements CustomerSessionStore {
    private static final int DEFAULT_MAX_SESSIONS = 10_000;
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(24);

    private final Clock clock;
    private final int maxSessions;
    private final Duration sessionTtl;
    private final ConcurrentMap<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotencyRecord> idempotencyRecords = new ConcurrentHashMap<>();
    private final AtomicLong insertionSequence = new AtomicLong();

    public InMemoryCustomerSessionStore() {
        this(Clock.systemUTC(), DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_TTL);
    }

    public InMemoryCustomerSessionStore(Clock clock, int maxSessions, Duration sessionTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be positive");
        }
        this.maxSessions = maxSessions;
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
    }

    @Override
    public synchronized Optional<SessionSnapshot> find(String sessionId, Instant now) {
        evictExpiredAndOverCapacity(Objects.requireNonNull(now, "now"));
        return Optional.ofNullable(sessions.get(sessionId)).map(StoredSession::snapshot);
    }

    @Override
    public synchronized Optional<IdempotencyRecord> findIdempotency(String key, Instant now) {
        evictExpiredAndOverCapacity(Objects.requireNonNull(now, "now"));
        return Optional.ofNullable(idempotencyRecords.get(key));
    }

    @Override
    public synchronized SessionSnapshot create(String sessionId, CustomerServiceResult result,
                                                List<CustomerConversation.Message> messages,
                                                List<CustomerConversation.RetrievalTrace> retrievals,
                                                Instant createdAt) {
        evictExpiredAndOverCapacity(clock.instant());
        SessionSnapshot snapshot = new SessionSnapshot(sessionId, result, createdAt, messages, retrievals);
        sessions.put(sessionId, new StoredSession(snapshot, insertionSequence.incrementAndGet()));
        evictExpiredAndOverCapacity(clock.instant());
        return snapshot;
    }

    @Override
    public synchronized SessionSnapshot update(SessionSnapshot snapshot) {
        evictExpiredAndOverCapacity(clock.instant());
        StoredSession previous = sessions.get(snapshot.sessionId());
        long sequence = previous == null ? insertionSequence.incrementAndGet() : previous.sequence();
        sessions.put(snapshot.sessionId(), new StoredSession(snapshot, sequence));
        evictExpiredAndOverCapacity(clock.instant());
        return snapshot;
    }

    @Override
    public synchronized void rememberIdempotency(String key, String question, String sessionId, Instant createdAt) {
        evictExpiredAndOverCapacity(clock.instant());
        idempotencyRecords.put(key, new IdempotencyRecord(question, sessionId, createdAt));
    }

    @Override
    public synchronized List<SessionSnapshot> withTickets(Instant now) {
        evictExpiredAndOverCapacity(Objects.requireNonNull(now, "now"));
        return sessions.values().stream()
                .map(StoredSession::snapshot)
                .filter(snapshot -> snapshot.result().ticket() != null)
                .sorted(Comparator.comparing(SessionSnapshot::createdAt).thenComparing(SessionSnapshot::sessionId))
                .toList();
    }

    @Override
    public synchronized int count(Instant now) {
        evictExpiredAndOverCapacity(Objects.requireNonNull(now, "now"));
        return sessions.size();
    }

    private void evictExpiredAndOverCapacity(Instant now) {
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue().snapshot().createdAt(), now));
        idempotencyRecords.entrySet().removeIf(entry -> isExpired(entry.getValue().createdAt(), now));
        while (sessions.size() > maxSessions) {
            Map.Entry<String, StoredSession> oldest = sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Comparator.comparingLong(StoredSession::sequence)))
                    .orElse(null);
            if (oldest == null || !sessions.remove(oldest.getKey(), oldest.getValue())) {
                return;
            }
            idempotencyRecords.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(oldest.getKey()));
        }
    }

    private boolean isExpired(Instant createdAt, Instant now) {
        return !createdAt.plus(sessionTtl).isAfter(now);
    }

    private record StoredSession(SessionSnapshot snapshot, long sequence) {
    }
}
