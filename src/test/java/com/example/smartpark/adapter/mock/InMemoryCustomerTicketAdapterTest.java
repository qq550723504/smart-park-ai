package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCustomerTicketAdapterTest {

    @Test
    void createsTheFirstTicketWithACustomerServiceId() {
        InMemoryCustomerTicketAdapter adapter = new InMemoryCustomerTicketAdapter();

        CustomerTicket ticket = adapter.create("cs-1", "REPAIR", "A1 restroom leak", Instant.parse("2026-08-23T02:00:00Z"));

        assertThat(ticket.id()).isEqualTo("CS-0001");
        assertThat(ticket.status()).isEqualTo("WAITING_AGENT");
        assertThat(adapter.list()).containsExactly(ticket);
    }

    @Test
    void followsTheLegalTicketLifecycle() {
        InMemoryCustomerTicketAdapter adapter = new InMemoryCustomerTicketAdapter();
        CustomerTicket ticket = adapter.create("cs-1", "REPAIR", "A1 restroom leak", Instant.parse("2026-08-23T02:00:00Z"));

        CustomerTicket assigned = adapter.update(ticket.id(), CustomerTicketStatus.ASSIGNED);
        CustomerTicket inProgress = adapter.update(ticket.id(), CustomerTicketStatus.IN_PROGRESS);
        CustomerTicket resolved = adapter.update(ticket.id(), CustomerTicketStatus.RESOLVED);
        CustomerTicket closed = adapter.update(ticket.id(), CustomerTicketStatus.CLOSED);

        assertThat(List.of(assigned, inProgress, resolved, closed))
                .extracting(CustomerTicket::status)
                .containsExactly("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED");
        assertThat(adapter.list()).containsExactly(closed);
    }

    @Test
    void rejectsUnknownTickets() {
        InMemoryCustomerTicketAdapter adapter = new InMemoryCustomerTicketAdapter();

        assertThatThrownBy(() -> adapter.update("CS-9999", CustomerTicketStatus.ASSIGNED))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("CS-9999");
    }
}
