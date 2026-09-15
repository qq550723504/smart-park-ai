package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

import java.util.List;
import java.util.Objects;

/**
 * Adapts a get-only {@link SecurityPort} to the {@link SecurityEventReader} contract the
 * aggregate consumes, so a deployment that registered the original port before readers
 * existed still starts and resolves its events.
 *
 * <p>{@link #listEvents()} is empty because the port cannot enumerate its events; the
 * catalog therefore falls back to {@link #getEvent(String)} for this reader instead of
 * treating an empty listing as "not found".</p>
 */
public final class SecurityPortReader implements SecurityEventReader {

    private final SecurityPort port;

    public SecurityPortReader(SecurityPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    @Override
    public List<SecurityEvent> listEvents() {
        return List.of();
    }

    @Override
    public SecurityEvent getEvent(String eventId) {
        return port.getEvent(eventId);
    }
}
