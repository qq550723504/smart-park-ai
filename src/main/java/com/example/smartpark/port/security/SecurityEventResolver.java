package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;

/**
 * Optional extension of {@link SecurityPort} for callers that already hold the
 * source-qualified identity of an event. Resolving through the identity keeps two
 * sources that reuse the same source-local id apart; a plain {@link SecurityPort}
 * can only look up the event id.
 */
public interface SecurityEventResolver extends SecurityPort {

    SecurityEvent getEvent(SecurityEventIdentity identity);

    /**
     * Resolves a reference emitted by {@link SecurityEventIdentity#reference()}: a
     * source-qualified token resolves to that exact source, while a legacy bare token
     * aliases any source of the same event id. The token carries the source but not the
     * location, so a match spanning several locations is ambiguous; callers holding the
     * owning alert should use {@link #getEvent(SecurityEventIdentity)} instead.
     */
    SecurityEvent getEventByReference(String reference);
}
