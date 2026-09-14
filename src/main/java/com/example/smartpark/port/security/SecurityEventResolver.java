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
}
