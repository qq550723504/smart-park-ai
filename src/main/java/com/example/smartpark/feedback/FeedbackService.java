package com.example.smartpark.feedback;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FeedbackService {
    private static final Set<String> TARGET_TYPES = Set.of("CUSTOMER_SESSION", "ALERT_WORKFLOW");
    private final Clock clock;
    private final CopyOnWriteArrayList<FeedbackEntry> entries = new CopyOnWriteArrayList<>();

    public FeedbackService() {
        this(Clock.systemUTC());
    }

    FeedbackService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FeedbackEntry record(String targetType, String targetId, FeedbackRating rating, String actorRole) {
        if (!TARGET_TYPES.contains(targetType)) throw new IllegalArgumentException("Unsupported feedback target type");
        FeedbackEntry entry = new FeedbackEntry(targetType, targetId, rating, actorRole, Instant.now(clock));
        entries.add(entry);
        return entry;
    }

    public List<FeedbackEntry> entries() {
        return List.copyOf(entries);
    }

    public long positiveCount() {
        return entries.stream().filter(entry -> entry.rating().positive()).count();
    }
}
