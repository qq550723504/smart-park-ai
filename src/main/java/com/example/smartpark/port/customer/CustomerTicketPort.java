package com.example.smartpark.port.customer;

import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;

import java.time.Instant;
import java.util.List;

public interface CustomerTicketPort {
    CustomerTicket create(String sessionId, String intent, String safeSummary, Instant createdAt);

    List<CustomerTicket> list();

    CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus);

    default CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus, Instant updatedAt) {
        return update(ticketId, nextStatus);
    }

    void deleteBySessionId(String sessionId);
}
