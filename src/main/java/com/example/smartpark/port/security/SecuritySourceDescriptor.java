package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;

import java.util.Objects;
import java.util.Set;

/**
 * Describes what a security source adapter can provide. {@code sourceId} is a
 * safe logical identifier; credentials and internal URLs are rejected.
 * {@code dispositionFeed} marks a source that can actually supply review
 * outcomes, which is required before false-positive statistics are advertised.
 */
public record SecuritySourceDescriptor(
        String sourceId,
        SecuritySourceType sourceType,
        Set<SecurityEventType> connectedEventTypes,
        boolean productionSource,
        boolean dispositionFeed) {

    /**
     * Convenience for sources that do not provide a disposition feed.
     */
    public SecuritySourceDescriptor(String sourceId, SecuritySourceType sourceType,
                                    Set<SecurityEventType> connectedEventTypes, boolean productionSource) {
        this(sourceId, sourceType, connectedEventTypes, productionSource, false);
    }

    public SecuritySourceDescriptor {
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = new SecuritySourceRef(sourceType, sourceId).sourceId();
        connectedEventTypes = connectedEventTypes == null ? Set.of() : Set.copyOf(connectedEventTypes);
    }
}
