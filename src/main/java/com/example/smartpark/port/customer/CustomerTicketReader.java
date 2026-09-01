package com.example.smartpark.port.customer;

import com.example.smartpark.model.customer.CustomerTicket;

import java.util.List;

/**
 * Lifecycle-aware read boundary for customer tickets.
 *
 * Unlike {@link CustomerTicketPort#list()}, implementations must exclude
 * tickets whose owning customer session has expired before returning them.
 */
@FunctionalInterface
public interface CustomerTicketReader {
    List<CustomerTicket> listActive();
}
