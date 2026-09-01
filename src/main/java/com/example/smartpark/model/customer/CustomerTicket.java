package com.example.smartpark.model.customer;

import java.time.Instant;
import java.util.Objects;

public record CustomerTicket(
        String id,
        String sessionId,
        String intent,
        String status,
        String safeSummary,
        Instant createdAt,
        Instant updatedAt) {

    public CustomerTicket(String id, String sessionId, String intent, String status,
                          String safeSummary, Instant createdAt) {
        this(id, sessionId, intent, status, safeSummary, createdAt, createdAt);
    }

    public CustomerTicket {
        id = requireText(id, "id");
        sessionId = requireText(sessionId, "sessionId");
        intent = requireText(intent, "intent");
        status = CustomerTicketStatus.valueOf(requireText(status, "status")).name();
        safeSummary = requireText(safeSummary, "safeSummary");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public CustomerTicket transitionTo(CustomerTicketStatus nextStatus, Instant updatedAt) {
        CustomerTicketStatus current = CustomerTicketStatus.valueOf(status);
        if (!isAllowed(current, nextStatus)) {
            throw new IllegalStateException("Ticket cannot transition from " + current + " to " + nextStatus);
        }
        return new CustomerTicket(id, sessionId, intent, nextStatus.name(), safeSummary, createdAt, updatedAt);
    }

    public CustomerTicket transitionTo(CustomerTicketStatus nextStatus) {
        return transitionTo(nextStatus, Instant.now());
    }

    private static boolean isAllowed(CustomerTicketStatus current, CustomerTicketStatus next) {
        return switch (current) {
            case WAITING_AGENT -> next == CustomerTicketStatus.ASSIGNED || next == CustomerTicketStatus.CANCELLED;
            case ASSIGNED -> next == CustomerTicketStatus.IN_PROGRESS || next == CustomerTicketStatus.CANCELLED;
            case IN_PROGRESS -> next == CustomerTicketStatus.WAITING_CUSTOMER || next == CustomerTicketStatus.RESOLVED;
            case WAITING_CUSTOMER -> next == CustomerTicketStatus.IN_PROGRESS || next == CustomerTicketStatus.RESOLVED;
            case RESOLVED -> next == CustomerTicketStatus.CLOSED || next == CustomerTicketStatus.IN_PROGRESS;
            case CLOSED, CANCELLED -> false;
        };
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
