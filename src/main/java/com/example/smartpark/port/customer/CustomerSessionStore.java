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

    void rememberIdempotency(String key, IdempotencyScope scope, String question,
                             CustomerServiceResult result, Instant createdAt);

    List<String> evict(Instant now);

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

    enum IdempotencyOperation {
        HANDLE,
        REPLY
    }

    record IdempotencyScope(IdempotencyOperation operation, String targetSessionId) {
        public IdempotencyScope {
            operation = Objects.requireNonNull(operation, "operation");
            if (operation == IdempotencyOperation.HANDLE && targetSessionId != null) {
                throw new IllegalArgumentException("handle idempotency cannot target a session");
            }
            if (operation == IdempotencyOperation.REPLY) {
                targetSessionId = requireText(targetSessionId, "targetSessionId");
            }
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value.trim();
        }
    }

    record IdempotencyRecord(IdempotencyScope scope, String question,
                             CustomerServiceResult result, Instant createdAt) {
        public IdempotencyRecord {
            scope = Objects.requireNonNull(scope, "scope");
            question = Objects.requireNonNull(question, "question");
            result = Objects.requireNonNull(result, "result");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
