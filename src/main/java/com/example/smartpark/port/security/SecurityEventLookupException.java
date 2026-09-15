package com.example.smartpark.port.security;

/**
 * A lookup failure whose message is safe to return to external consumers because it
 * describes the caller's own reference (for example a bare event id that maps to
 * several concrete sources) rather than any vendor-internal detail.
 *
 * <p>Adapter failures are deliberately not reported through this type: their messages
 * can carry connection URLs or credential-bearing configuration labels, so callers
 * rendering tool output must treat any other exception as unavailable rather than
 * echoing it.
 */
public class SecurityEventLookupException extends IllegalArgumentException {

    public SecurityEventLookupException(String message) {
        super(message);
    }
}
