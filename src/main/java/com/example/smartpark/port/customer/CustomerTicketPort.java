package com.example.smartpark.port.customer;

import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;

import java.time.Instant;
import java.util.List;

public interface CustomerTicketPort {
    CustomerTicket create(String sessionId, String intent, String safeSummary, Instant createdAt);

    List<CustomerTicket> list();

    default CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus) {
        return update(ticketId, nextStatus, Instant.now());
    }

    CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus, Instant updatedAt);

    void deleteBySessionId(String sessionId);
}
