package com.example.smartpark.voice.port;

import com.example.smartpark.voice.model.VoiceErrorCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic fake for session-layer tests. Test code scripts chunk/error/
 * completion emission explicitly; at-most-one terminal event per turn and
 * late-event dropping are guaranteed.
 */
public final class FakeStreamingTtsPort implements StreamingTtsPort {

    private final Map<String, Listener> listenersBySession = new ConcurrentHashMap<>();
    private final Map<String, List<String>> textsBySession = new ConcurrentHashMap<>();
    private final AtomicInteger sequenceCounter = new AtomicInteger();

    private final Map<String, String> activeTurnBySession = new ConcurrentHashMap<>();

    @Override
    public void start(String sessionId, String turnId, List<String> textSegments, Listener listener) {
        listenersBySession.put(sessionId, listener);
        activeTurnBySession.put(sessionId, turnId);
        textsBySession.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                .addAll(textSegments);
    }

    @Override
    public void cancel(String sessionId, String turnId) {
        interruptTurn(sessionId, turnId);
    }

    public void completeTurn(String sessionId, String turnId) {
        withActiveListener(sessionId, l -> l.onCompleted(sessionId, turnId));
        listenersBySession.remove(sessionId);
    }

    public void failTurn(String sessionId, String turnId, VoiceErrorCode code) {
        withActiveListener(sessionId, l -> l.onError(sessionId, turnId, code));
        listenersBySession.remove(sessionId);
    }

    public void interruptTurn(String sessionId, String turnId) {
        if (!turnId.equals(activeTurnBySession.get(sessionId))) {
            return; // unknown or superseded turn: idempotent no-op
        }
        withActiveListener(sessionId, l -> l.onInterrupted(sessionId, turnId));
        listenersBySession.remove(sessionId);
        activeTurnBySession.remove(sessionId);
    }

    public void emitChunk(String sessionId, String turnId, byte[] audio) {
        withActiveListener(sessionId, l ->
                l.onAudioChunk(sessionId, turnId, sequenceCounter.incrementAndGet(), audio.clone()));
    }

    public List<String> requestedTexts(String sessionId) {
        return List.copyOf(textsBySession.getOrDefault(sessionId, List.of()));
    }

    private void withActiveListener(String sessionId, java.util.function.Consumer<Listener> action) {
        Listener listener = listenersBySession.get(sessionId);
        if (listener != null) {
            action.accept(listener);
        }
    }
}
