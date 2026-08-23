package com.example.smartpark.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CustomerConversation(
        String sessionId,
        List<Message> messages,
        List<RetrievalTrace> retrievals,
        boolean humanHandoff) {
    public CustomerConversation {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        retrievals = List.copyOf(Objects.requireNonNull(retrievals, "retrievals"));
    }

    public record Message(String role, String text, Instant createdAt) {
        public Message {
            role = Objects.requireNonNull(role, "role");
            text = Objects.requireNonNull(text, "text");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record RetrievalTrace(String query, List<String> documentIds, Instant createdAt) {
        public RetrievalTrace {
            query = Objects.requireNonNull(query, "query");
            documentIds = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
