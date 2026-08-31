package com.example.smartpark.voice.adapter.dashscope;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionOptions;
import com.alibaba.cloud.ai.dashscope.audio.transcription.RecognitionResult;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingAsrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real DashScope streaming ASR adapter. Wraps the 2.0 streaming transcription
 * model, opens one provider stream per turn, serializes SDK callbacks through a
 * single thread onto session frames, and maps provider failures onto safe error
 * codes so raw vendor detail never reaches clients, events, or logs.
 */
public final class DashScopeStreamingAsrAdapter implements StreamingAsrPort, DisposableBean {

    /** Seam over the SDK class so contract tests can inject a local fake. */
    @FunctionalInterface
    public interface AsrSdkFacade {
        Flux<RecognitionResult> streamRecognition(
                Flux<ByteBuffer> audio, DashScopeAudioTranscriptionOptions options);
    }

    private static final Logger log = LoggerFactory.getLogger(DashScopeStreamingAsrAdapter.class);

    private final AsrSdkFacade facade;
    private final DashScopeAudioTranscriptionOptions options;
    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dashscope-asr-callbacks");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, TurnState> activeTurnsBySession = new ConcurrentHashMap<>();

    public DashScopeStreamingAsrAdapter(
            DashScopeAudioTranscriptionModel transcriptionModel,
            DashScopeAudioTranscriptionOptions configuredOptions) {
        this(transcriptionModel::streamRecognition, configuredOptions);
    }

    DashScopeStreamingAsrAdapter(AsrSdkFacade facade,
                                 DashScopeAudioTranscriptionOptions configuredOptions) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.options = ModelOptionsUtils.mapToClass(
                ModelOptionsUtils.objectToMap(
                        Objects.requireNonNull(configuredOptions, "configuredOptions")),
                DashScopeAudioTranscriptionOptions.class);
        // Exactly the one accepted capture format; anything else is rejected upstream.
        this.options.setFormat("pcm");
        this.options.setSampleRate(16_000);
    }

    @Override
    public void start(String sessionId, String turnId, Listener listener) {
        Objects.requireNonNull(listener, "listener");
        TurnState created = new TurnState(turnId);
        TurnState previous =
                activeTurnsBySession.putIfAbsent(sessionId, created);
        if (previous != null) {
            throw new IllegalStateException(
                    "session " + sessionId + " already has an active ASR turn");
        }
        Flux<RecognitionResult> upstream =
                facade.streamRecognition(created.input.asFlux(), options);
        created.subscription = upstream.subscribe(
                result -> dispatchIfActive(sessionId, created, () -> onRecognitionResult(
                        sessionId, turnId, listener, result)),
                error -> dispatchIfActive(sessionId, created, () -> failTurn(
                        sessionId, listener, error)),
                () -> dispatchIfActive(sessionId, created, () -> finishTurn(
                        sessionId, listener)));
    }

    @Override
    public void send(String sessionId, String turnId, byte[] pcmChunk) {
        TurnState state = requireActive(sessionId, turnId);
        // Fast path first; busy-looping handler only spins on the rare
        // FAIL_NON_SERIALIZED race instead of a hand-rolled yield retry loop.
        Sinks.EmitResult result = state.input.tryEmitNext(ByteBuffer.wrap(pcmChunk.clone()));
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            state.input.emitNext(ByteBuffer.wrap(pcmChunk.clone()),
                    Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
        } else if (result.isFailure()) {
            throw new IllegalStateException("failed to feed ASR input: " + result);
        }
    }

    @Override
    public void commit(String sessionId, String turnId) {
        TurnState state = requireActive(sessionId, turnId);
        state.inputCommitted.set(true);
        state.input.tryEmitComplete();
    }

    @Override
    public void cancel(String sessionId, String turnId) {
        TurnState state = activeTurnsBySession.get(sessionId);
        if (state == null || !state.turnId.equals(turnId)) {
            return; // unknown/superseded turn: cancellation is a no-op
        }
        if (!activeTurnsBySession.remove(sessionId, state)) {
            return;
        }
        state.finished.set(true);
        state.input.tryEmitComplete();
        if (state.subscription != null) {
            state.subscription.dispose();
        }
    }

    @Override
    public void destroy() {
        callbackExecutor.shutdownNow();
    }

    private TurnState requireActive(String sessionId, String turnId) {
        TurnState state = activeTurnsBySession.get(sessionId);
        if (state == null || !state.turnId.equals(turnId) || state.finished.get()) {
            throw new IllegalStateException("no active ASR turn " + turnId + " for " + sessionId);
        }
        return state;
    }

    private void dispatchIfActive(String sessionId, TurnState state, Runnable action) {
        // Serialized onto one thread; late events for finished turns are dropped here.
        callbackExecutor.execute(() -> {
            if (!state.finished.get()
                    && activeTurnsBySession.get(sessionId) == state) {
                action.run();
            }
        });
    }

    private void onRecognitionResult(String sessionId, String turnId,
                                     Listener listener, RecognitionResult result) {
        String text = result.getText();
        if (text == null || text.isBlank()) {
            return; // heartbeats carry no transcript content
        }
        boolean finalSentence =
                result.sentence() != null
                        && Boolean.TRUE.equals(result.sentence().sentenceEnd());
        if (finalSentence) {
            TurnState state = activeTurnsBySession.get(sessionId);
            if (state != null && state.turnId.equals(turnId) && !state.finalSent) {
                // Exactly one final per turn: later finals are duplicates and
                // must not reach the listener twice.
                state.finalSent = true;
            } else if (state != null && state.finalSent) {
                return;
            }
            listener.onFinal(sessionId, turnId, text.trim());
        } else if (!isFinalAlreadySent(sessionId, turnId)) {
            listener.onPartial(sessionId, turnId, text.trim());
        }
    }

    private boolean isFinalAlreadySent(String sessionId, String turnId) {
        TurnState state = activeTurnsBySession.get(sessionId);
        return state != null && state.turnId.equals(turnId) && state.finalSent;
    }

    private void failTurn(String sessionId, Listener listener, Throwable error) {
        // Log full detail server-side; surface only the safe code downstream.
        log.warn("dashscope streaming asr failed for session {}: {}",
                sessionId, error.getClass().getSimpleName());
        try {
            listener.onError(sessionId, currentTurnId(sessionId), VoiceErrorCode.PROVIDER_FAILURE);
        } finally {
            closeQuietly(sessionId, listener);
        }
    }

    private void finishTurn(String sessionId, Listener listener) {
        closeQuietly(sessionId, listener);
    }

    private void closeQuietly(String sessionId, Listener listener) {
        TurnState state = activeTurnsBySession.remove(sessionId);
        if (state != null) {
            state.finished.set(true);
        }
        listener.onClosed(sessionId, state != null ? state.turnId : null);
    }

    private String currentTurnId(String sessionId) {
        TurnState state = activeTurnsBySession.get(sessionId);
        return state != null ? state.turnId : null;
    }

    private static final class TurnState {
        final String turnId;
        final Sinks.Many<ByteBuffer> input = Sinks.many().unicast().onBackpressureBuffer();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean inputCommitted = new AtomicBoolean(false);
        volatile boolean finalSent;
        volatile Disposable subscription;

        private TurnState(String turnId) {
            this.turnId = turnId;
        }
    }
}
