package com.example.smartpark.voice.port;

import com.example.smartpark.voice.model.VoiceErrorCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic fake for session-layer tests. Test code scripts transcript
 * events explicitly; the fake guarantees at-most-one-terminal-event semantics
 * and drops every event for turns that were never started or already closed.
 */
public final class FakeStreamingAsrPort implements StreamingAsrPort {

    private final Map<String, Listener> listenersByTurn = new ConcurrentHashMap<>();
    private final Map<String, List<byte[]>> chunksBySession = new ConcurrentHashMap<>();
    private final List<String> started = new CopyOnWriteArrayList<>();
    private final List<String> committed = new CopyOnWriteArrayList<>();
    private final List<String> cancelled = new CopyOnWriteArrayList<>();

    @Override
    public void start(String sessionId, String turnId, Listener listener) {
        started.add(sessionId + "/" + turnId);
        listenersByTurn.put(turnKey(sessionId, turnId), listener);
    }

    @Override
    public void send(String sessionId, String turnId, byte[] pcmChunk) {
        requireActive(sessionId, turnId);
        chunksBySession.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                .add(pcmChunk.clone());
    }

    @Override
    public void commit(String sessionId, String turnId) {
        requireActive(sessionId, turnId);
        committed.add(turnId);
    }

    @Override
    public void cancel(String sessionId, String turnId) {
        requireActive(sessionId, turnId);
        cancelled.add(turnId);
        listenersByTurn.remove(turnKey(sessionId, turnId));
    }

    public void emitPartial(String sessionId, String turnId, String text) {
        withActiveListener(sessionId, turnId, l -> l.onPartial(sessionId, turnId, text));
    }

    public void emitFinal(String sessionId, String turnId, String text) {
        withActiveListener(sessionId, turnId, l -> {
            l.onFinal(sessionId, turnId, text);
            l.onClosed(sessionId, turnId);
        });
        finishTurn(sessionId, turnId);
    }

    public void failTurn(String sessionId, String turnId, VoiceErrorCode code) {
        withActiveListener(sessionId, turnId, l -> {
            l.onError(sessionId, turnId, code);
            l.onClosed(sessionId, turnId);
        });
        finishTurn(sessionId, turnId);
    }

    public void closeTurn(String sessionId, String turnId) {
        withActiveListener(sessionId, turnId, l -> l.onClosed(sessionId, turnId));
        finishTurn(sessionId, turnId);
    }

    public List<String> startedTurns() {
        return List.copyOf(started);
    }

    public List<byte[]> sentChunks(String sessionId) {
        return List.copyOf(chunksBySession.getOrDefault(sessionId, List.of()));
    }

    public List<String> committedTurns() {
        return List.copyOf(committed);
    }

    public List<String> cancelledTurns() {
        return List.copyOf(cancelled);
    }

    public List<String> activeTurnIds() {
        return listenersByTurn.keySet().stream().map(k -> k.substring(k.indexOf('/') + 1)).toList();
    }

    private void requireActive(String sessionId, String turnId) {
        if (!listenersByTurn.containsKey(turnKey(sessionId, turnId))) {
            throw new IllegalStateException("turn not active: " + sessionId + "/" + turnId);
        }
    }

    private void withActiveListener(String sessionId, String turnId, java.util.function.Consumer<Listener> action) {
        Listener listener = listenersByTurn.get(turnKey(sessionId, turnId));
        if (listener != null) {
            action.accept(listener);
        }
    }

    private void finishTurn(String sessionId, String turnId) {
        listenersByTurn.remove(turnKey(sessionId, turnId));
    }

    private static String turnKey(String sessionId, String turnId) {
        return sessionId + "/" + turnId;
    }
}
