package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates registered source adapters into honest capability reporting.
 * Types without a declared source stay {@code NOT_READY}; only an adapter that
 * explicitly declares itself as a production source can yield {@code AVAILABLE}.
 */
public final class SecurityEventCapabilityRegistry {

    private final List<SecuritySourceAdapter> adapters;

    public SecurityEventCapabilityRegistry(List<SecuritySourceAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    public Set<SecurityEventType> modelSupportedTypes() {
        return Collections.unmodifiableSet(EnumSet.allOf(SecurityEventType.class));
    }

    public List<SecurityEventCapability> capabilities() {
        List<SecurityEventCapability> capabilities = new ArrayList<>();
        for (SecurityEventType type : SecurityEventType.values()) {
            boolean connected = false;
            boolean production = false;
            for (SecuritySourceAdapter adapter : adapters) {
                SecuritySourceDescriptor descriptor = adapter.descriptor();
                if (descriptor.connectedEventTypes().contains(type)) {
                    connected = true;
                    if (descriptor.productionSource()) production = true;
                }
            }
            capabilities.add(SecurityEventCapability.of(type, connected, production));
        }
        return List.copyOf(capabilities);
    }

    /**
     * Disposition/false-positive statistics require a source that explicitly
     * declares a disposition feed; a connected event feed alone only proves
     * events arrive, not that review outcomes can be produced.
     */
    public boolean dispositionEnabled() {
        return adapters.stream().anyMatch(adapter -> {
            SecuritySourceDescriptor descriptor = adapter.descriptor();
            return descriptor.productionSource() && descriptor.dispositionFeed();
        });
    }
}
