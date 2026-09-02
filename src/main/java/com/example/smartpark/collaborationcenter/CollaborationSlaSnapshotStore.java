package com.example.smartpark.collaborationcenter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class CollaborationSlaSnapshotStore {
    public static final int MAX_SNAPSHOTS = 120;
    public static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);

    private final Deque<CollaborationSlaSnapshot> snapshots = new ArrayDeque<>();

    public synchronized boolean recordIfDue(Instant capturedAt, List<CollaborationWorkItem> items) {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(items, "items");
        CollaborationSlaSnapshot latest = snapshots.peekLast();
        if (latest != null && capturedAt.isBefore(latest.capturedAt().plus(SAMPLE_INTERVAL))) {
            return false;
        }
        snapshots.addLast(snapshot(capturedAt, items));
        while (snapshots.size() > MAX_SNAPSHOTS) snapshots.removeFirst();
        return true;
    }

    public synchronized List<CollaborationSlaSnapshot> list(int limit) {
        if (limit < 1 || limit > MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_SNAPSHOTS);
        }
        List<CollaborationSlaSnapshot> copy = new ArrayList<>(snapshots);
        int from = Math.max(0, copy.size() - limit);
        return List.copyOf(copy.subList(from, copy.size()));
    }

    private static CollaborationSlaSnapshot snapshot(Instant capturedAt, List<CollaborationWorkItem> items) {
        int overdue = 0;
        int dueSoon = 0;
        int onTrack = 0;
        int completed = 0;
        int notApplicable = 0;
        for (CollaborationWorkItem item : items) {
            switch (item.slaState()) {
                case OVERDUE -> overdue++;
                case DUE_SOON -> dueSoon++;
                case ON_TRACK -> onTrack++;
                case COMPLETED -> completed++;
                case NOT_APPLICABLE -> notApplicable++;
            }
        }
        return new CollaborationSlaSnapshot(capturedAt, items.size(), overdue, dueSoon, onTrack, completed, notApplicable);
    }
}
