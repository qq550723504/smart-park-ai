package com.example.smartpark.voice;

import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory registry of active voice sessions; entries are owned by the service. */
public class VoiceSessionStore {

    /** Immutable session summary for the REST layer; never exposes audio state. */
    public record Snapshot(String sessionId, UUID runId, Instant createdAt) {
    }

    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();

    /** Hard cap so abusive clients cannot exhaust memory with never-closed sessions. */
    private static final int MAX_ACTIVE_SESSIONS = 200;

    /** Creates and stores a new session with a fresh id and runId.
     *  @throws IllegalStateException when the active-session cap is reached */
    public VoiceSession create() {
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) {
            throw new IllegalStateException(
                    "too many active voice sessions (cap " + MAX_ACTIVE_SESSIONS + ")");
        }
        String sessionId = "vs-" + UUID.randomUUID();
        VoiceSession session = new VoiceSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<VoiceSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Removes and returns the session if present. */
    public Optional<VoiceSession> remove(String sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }

    public int size() {
        return sessions.size();
    }
}
