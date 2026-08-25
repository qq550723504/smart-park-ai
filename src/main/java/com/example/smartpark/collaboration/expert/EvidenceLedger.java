package com.example.smartpark.collaboration.expert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EvidenceLedger {
    private final Map<String, Observation> observations = Collections.synchronizedMap(new LinkedHashMap<>());

    public void record(String ref) {
        record(ref, "");
    }

    public void record(String ref, String result) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("evidence ref must not be blank");
        observations.put(ref.trim(), new Observation(ref.trim(), result == null ? "" : result));
    }

    public Set<String> snapshot() {
        synchronized (observations) { return Set.copyOf(observations.keySet()); }
    }

    public List<Observation> snapshotObservations() {
        synchronized (observations) { return List.copyOf(observations.values()); }
    }

    public record Observation(String ref, String result) {
        public Observation {
            if (ref == null || ref.isBlank()) throw new IllegalArgumentException("evidence ref must not be blank");
            ref = ref.trim();
            result = result == null ? "" : result;
        }
    }
}
