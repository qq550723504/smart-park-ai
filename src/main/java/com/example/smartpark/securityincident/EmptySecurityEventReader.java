package com.example.smartpark.securityincident;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityEventReader;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Empty legacy event source used when the application only registers
 * {@code SecuritySourceAdapter} beans, so incident correlation can run purely
 * from the adapter port.
 */
final class EmptySecurityEventReader implements SecurityEventReader {

    @Override
    public SecurityEvent getEvent(String eventId) {
        throw new NoSuchElementException("security event not found: " + eventId);
    }

    @Override
    public List<SecurityEvent> listEvents() {
        return List.of();
    }
}
