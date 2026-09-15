package com.example.smartpark.support;

import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.security.SecurityPortReader;
import org.springframework.beans.factory.ObjectProvider;

import java.util.NoSuchElementException;

/**
 * Internal helper shared by always-on components that need the {@link SecurityEventReader}
 * contract but must also start in a deployment that only registered the original get-only
 * {@link SecurityPort}. Not part of the application's public API.
 */
public final class SecurityEventReaders {

    private SecurityEventReaders() {
    }

    /**
     * Resolves the reader contract from the registered ports: a registered reader wins,
     * otherwise the first port is adapted so its events stay resolvable.
     */
    public static SecurityEventReader resolve(ObjectProvider<SecurityPort> securityPorts) {
        SecurityEventReader reader = securityPorts.orderedStream()
                .filter(SecurityEventReader.class::isInstance)
                .map(SecurityEventReader.class::cast)
                .findFirst().orElse(null);
        if (reader != null) return reader;
        SecurityPort port = securityPorts.orderedStream().findFirst().orElse(null);
        return port == null ? EMPTY : new SecurityPortReader(port);
    }

    private static final SecurityEventReader EMPTY = new SecurityPortReader(eventId -> {
        throw new NoSuchElementException("security event not found: " + eventId);
    });
}
