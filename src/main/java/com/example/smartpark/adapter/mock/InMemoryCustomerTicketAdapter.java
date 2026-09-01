package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerTicketPort;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryCustomerTicketAdapter implements CustomerTicketPort {
    private final AtomicInteger sequence = new AtomicInteger();
    private final ConcurrentMap<String, CustomerTicket> tickets = new ConcurrentHashMap<>();

    @Override
    public CustomerTicket create(String sessionId, String intent, String safeSummary, Instant createdAt) {
        String id = String.format("CS-%04d", sequence.incrementAndGet());
        CustomerTicket ticket = new CustomerTicket(id, sessionId, intent, CustomerTicketStatus.WAITING_AGENT.name(), safeSummary, createdAt);
        tickets.put(id, ticket);
        return ticket;
    }

    @Override
    public List<CustomerTicket> list() {
        return tickets.values().stream()
                .sorted(Comparator.comparing(CustomerTicket::createdAt).thenComparing(CustomerTicket::id))
                .toList();
    }

    @Override
    public CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus) {
        return update(ticketId, nextStatus, Instant.now());
    }

    @Override
    public CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus, Instant updatedAt) {
        Objects.requireNonNull(nextStatus, "nextStatus");
        Objects.requireNonNull(updatedAt, "updatedAt");
        return tickets.compute(ticketId, (id, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Unknown customer ticket: " + id);
            }
            return current.transitionTo(nextStatus, updatedAt);
        });
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        tickets.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }
}
