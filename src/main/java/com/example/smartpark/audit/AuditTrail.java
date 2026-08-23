package com.example.smartpark.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AuditTrail {
    private final Clock clock;
    private final CopyOnWriteArrayList<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public AuditTrail() {
        this(Clock.systemUTC());
    }

    AuditTrail(Clock clock) {
        this.clock = clock;
    }

    public void record(String role, String action, String resourceId, String outcome) {
        entries.add(new AuditEntry(role, action, resourceId, outcome, Instant.now(clock)));
    }

    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }
}
