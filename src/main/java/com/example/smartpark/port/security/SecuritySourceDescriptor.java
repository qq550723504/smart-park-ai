package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;

import java.util.Objects;
import java.util.Set;

/**
 * Describes what a security source adapter can provide. {@code sourceId} is a
 * safe logical identifier; credentials and internal URLs are rejected.
 */
public record SecuritySourceDescriptor(
        String sourceId,
        SecuritySourceType sourceType,
        Set<SecurityEventType> connectedEventTypes,
        boolean productionSource) {

    public SecuritySourceDescriptor {
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = new SecuritySourceRef(sourceType, sourceId).sourceId();
        connectedEventTypes = connectedEventTypes == null ? Set.of() : Set.copyOf(connectedEventTypes);
    }
}
