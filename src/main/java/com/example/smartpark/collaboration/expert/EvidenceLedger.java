package com.example.smartpark.collaboration.expert;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class EvidenceLedger {
    private final Set<String> refs = Collections.synchronizedSet(new LinkedHashSet<>());

    public void record(String ref) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("evidence ref must not be blank");
        refs.add(ref.trim());
    }

    public Set<String> snapshot() {
        synchronized (refs) { return Set.copyOf(refs); }
    }
}
