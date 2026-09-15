package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

public interface SecurityPort {

    /**
     * Resolves a security event by its source-local id. Implementations must throw
     * {@link java.util.NoSuchElementException} when no such event exists, so aggregating
     * callers can tell a genuine miss from a malformed request or a backend failure.
     */
    SecurityEvent getEvent(String eventId);
}
