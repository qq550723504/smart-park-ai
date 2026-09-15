package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEventType;

import java.util.Objects;

/**
 * Per-event-type capability. Keeps "the model supports this type" separate from
 * "this deployment has a real source connected". {@code state} never claims
 * availability without a connected source.
 */
public record SecurityEventCapability(
        SecurityEventType eventType,
        boolean modelSupported,
        boolean sourceConnected,
        boolean productionSource,
        State state) {

    public enum State {
        AVAILABLE,
        ADAPTED,
        NOT_READY
    }

    public SecurityEventCapability {
        eventType = Objects.requireNonNull(eventType, "eventType");
        state = Objects.requireNonNull(state, "state");
    }

    public static SecurityEventCapability of(SecurityEventType eventType,
                                             boolean sourceConnected,
                                             boolean productionSource) {
        State state = !sourceConnected
                ? State.NOT_READY
                : productionSource ? State.AVAILABLE : State.ADAPTED;
        return new SecurityEventCapability(eventType, true, sourceConnected, productionSource, state);
    }
}
