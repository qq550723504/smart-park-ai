package com.example.smartpark.voice.adapter.dashscope;

import com.alibaba.cloud.ai.dashscope.audio.tts.StreamingInputTextToSpeechModel;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real DashScope streaming TTS adapter. Accepts only validator-approved answer
 * text, merges text segments in order into the provider input stream,
 * serializes SDK callbacks through a single thread with per-turn guards, and
 * maps provider failures onto safe error codes. Cancellation disposes the
 * upstream subscription and publishes an interruption exactly once; late
 * callbacks are dropped by turnId.
 */
public final class DashScopeStreamingTtsAdapter implements StreamingTtsPort, DisposableBean {

    /** Seam over the SDK class so contract tests can inject a local fake. */
    @FunctionalInterface
    public interface TtsSdkFacade {
        Flux<org.springframework.ai.audio.tts.TextToSpeechResponse> streamSpeech(
                Flux<String> text, org.springframework.ai.audio.tts.TextToSpeechOptions options);
    }

    private static final Logger log = LoggerFactory.getLogger(DashScopeStreamingTtsAdapter.class);

    private final TtsSdkFacade facade;
    private final org.springframework.ai.audio.tts.TextToSpeechOptions options;
    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dashscope-tts-callbacks");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, TurnState> activeTurnsBySession = new ConcurrentHashMap<>();

    public DashScopeStreamingTtsAdapter(StreamingInputTextToSpeechModel speechModel) {
        this((TtsSdkFacade) speechModel::stream);
    }

    DashScopeStreamingTtsAdapter(TtsSdkFacade facade) {
        this(facade, null);
    }

    DashScopeStreamingTtsAdapter(TtsSdkFacade facade,
                                 org.springframework.ai.audio.tts.TextToSpeechOptions options) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.options = options == null
                ? org.springframework.ai.audio.tts.TextToSpeechOptions.builder().build()
                : options;
    }

    @Override
    public void start(String sessionId, String turnId, List<String> textSegments, Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (textSegments == null || textSegments.isEmpty()) {
            throw new IllegalArgumentException("text segments must not be empty");
        }
        TurnState created = new TurnState(turnId, listener);
        TurnState previous = activeTurnsBySession.putIfAbsent(sessionId, created);
        if (previous != null) {
            throw new IllegalStateException(
                    "session " + sessionId + " already has an active TTS turn");
        }
        // Text delta merge: segments reach the provider strictly in order.
        Flux<String> mergedText = Flux.fromIterable(textSegments).filter(segment -> !segment.isBlank());
        created.subscription = facade.streamSpeech(mergedText, options).subscribe(
                response -> dispatchIfActive(sessionId, created,
                        () -> emitChunk(sessionId, created, response)),
                error -> dispatchIfActive(sessionId, created,
                        () -> failTurn(sessionId, created, error)),
                () -> dispatchIfActive(sessionId, created,
                        () -> completeTurn(sessionId, created)));
    }

    @Override
    public void cancel(String sessionId, String turnId) {
        callbackExecutor.execute(() -> interruptTurn(sessionId, turnId));
    }

    private void emitChunk(String sessionId, TurnState state,
                           org.springframework.ai.audio.tts.TextToSpeechResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        int sequence = state.nextChunkSequence.getAndIncrement();
        state.listener.onAudioChunk(sessionId, state.turnId, sequence, response.getResult().getOutput());
    }

    private void completeTurn(String sessionId, TurnState state) {
        finish(sessionId, state);
        state.listener.onCompleted(sessionId, state.turnId);
    }

    private void failTurn(String sessionId, TurnState state, Throwable error) {
        // Full detail stays server-side; only the safe code crosses the boundary.
        log.warn("dashscope streaming tts failed for session {}: {}",
                sessionId, error.getClass().getSimpleName());
        finish(sessionId, state);
        state.listener.onError(sessionId, state.turnId, VoiceErrorCode.PROVIDER_FAILURE);
    }

    private void interruptTurn(String sessionId, String turnId) {
        TurnState state = activeTurnsBySession.get(sessionId);
        if (state == null || !state.turnId.equals(turnId)) {
            return; // unknown or superseded turn: idempotent no-op
        }
        finish(sessionId, state);
        if (state.subscription != null) {
            state.subscription.dispose();
        }
        // OUTPUT_INTERRUPTED equivalent: exactly once, terminal.
        state.listener.onInterrupted(sessionId, turnId);
    }

    private void dispatchIfActive(String sessionId, TurnState state, Runnable action) {
        // Serialized onto one thread; late events for finished turns drop here.
        callbackExecutor.execute(() -> {
            if (!state.finished.get() && activeTurnsBySession.get(sessionId) == state) {
                action.run();
            }
        });
    }

    private void finish(String sessionId, TurnState state) {
        state.finished.set(true);
        activeTurnsBySession.remove(sessionId, state);
    }

    @Override
    public void destroy() {
        callbackExecutor.shutdownNow();
    }

    private static final class TurnState {
        final String turnId;
        final StreamingTtsPort.Listener listener;
        final AtomicInteger nextChunkSequence = new AtomicInteger(1);
        final AtomicBoolean finished = new AtomicBoolean(false);
        volatile Disposable subscription;

        private TurnState(String turnId, StreamingTtsPort.Listener listener) {
            this.turnId = turnId;
            this.listener = listener;
        }
    }
}
