package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.CollaborationRun;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CollaborationRunStore {
    private final ConcurrentMap<UUID, CollaborationRun> runs = new ConcurrentHashMap<>();
    public CollaborationRun save(CollaborationRun run) { runs.put(run.runId(), run); return run; }
    public CollaborationRun get(UUID id) {
        CollaborationRun run = runs.get(id);
        if (run == null) throw new NoSuchElementException("Unknown collaboration run: " + id);
        return run;
    }
}
