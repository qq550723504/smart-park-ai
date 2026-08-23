package com.example.smartpark.port.customer;

import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.workflow.CustomerConversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface CustomerSessionStore {
    Optional<SessionSnapshot> find(String sessionId, Instant now);

    Optional<IdempotencyRecord> findIdempotency(String key, Instant now);

    SessionSnapshot create(String sessionId, CustomerServiceResult result,
                           List<CustomerConversation.Message> messages,
                           List<CustomerConversation.RetrievalTrace> retrievals,
                           Instant createdAt);

    SessionSnapshot update(SessionSnapshot snapshot);

    void rememberIdempotency(String key, String question, String sessionId, Instant createdAt);

    List<SessionSnapshot> withTickets(Instant now);

    int count(Instant now);

    record SessionSnapshot(
            String sessionId,
            CustomerServiceResult result,
            Instant createdAt,
            List<CustomerConversation.Message> messages,
            List<CustomerConversation.RetrievalTrace> retrievals) {
        public SessionSnapshot {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            result = Objects.requireNonNull(result, "result");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            retrievals = List.copyOf(Objects.requireNonNull(retrievals, "retrievals"));
        }
    }

    record IdempotencyRecord(String question, String sessionId, Instant createdAt) {
        public IdempotencyRecord {
            question = Objects.requireNonNull(question, "question");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
