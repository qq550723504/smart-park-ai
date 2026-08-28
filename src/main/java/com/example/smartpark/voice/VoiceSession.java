package com.example.smartpark.voice;

import com.example.smartpark.voice.audio.AudioFrameValidator;
import com.example.smartpark.voice.audio.VoiceAudioRingBuffer;
import com.example.smartpark.voice.model.VoiceSessionStateMachine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-session state container: pure state machine, serial executor, audio
 * validator and in-memory ring buffer. All session work is funneled through
 * {@link #execute(Runnable)} so callbacks from ASR/TTS ports serialize.
 */
public final class VoiceSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoiceSession.class);

    private final String sessionId;
    private final UUID runId = UUID.randomUUID();
    private final Instant createdAt = Instant.now();

    private final VoiceSessionStateMachine stateMachine = new VoiceSessionStateMachine();
    private final AtomicLong frameSequence = new AtomicLong();

    private final ExecutorService serialExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AudioFrameValidator audioValidator;
    private final VoiceAudioRingBuffer ringBuffer;

    /** Per-turn bookkeeping; guarded by the serial executor except volatile reads. */
    private volatile String currentTurnId;
    private volatile String pendingFinalText;
    private final AtomicLong turnCounter = new AtomicLong();
    private final AtomicBoolean turnInterrupted = new AtomicBoolean(false);
    private volatile long receivedChunkCount;

    public VoiceSession(String sessionId) {
        this(sessionId, Duration.ofSeconds(10));
    }

    public VoiceSession(String sessionId, Duration maxInputDuration) {
        this.sessionId = sessionId;
        this.serialExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voice-session-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });
        this.audioValidator = new AudioFrameValidator(
                new com.example.smartpark.voice.audio.AudioFormatSpec(),
                maxInputDuration, 8192, 1000);
        this.ringBuffer = new VoiceAudioRingBuffer(
                new com.example.smartpark.voice.audio.AudioFormatSpec(),
                maxInputDuration);
    }

    public void execute(Runnable action) {
        if (closed.get()) {
            return;
        }
        try {
            serialExecutor.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException ex) {
                    // Never let a task exception silently kill the worker thread:
                    // raw details stay server-side (log hygiene), no rethrow.
                    log.warn("voice session task failed in {}: {}",
                            sessionId, ex.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // session closing concurrently: drop the action
        }
    }

    public boolean tryApply(com.example.smartpark.voice.model.VoiceSessionEvent event) {
        return stateMachine.canApply(event) && stateMachine.apply(event) != null;
    }

    /**
     * Applies the event and returns the outcome, or null when illegal.
     */
    public VoiceSessionStateMachine.Transition apply(
            com.example.smartpark.voice.model.VoiceSessionEvent event) {
        if (!stateMachine.canApply(event)) {
            return null;
        }
        return stateMachine.apply(event);
    }

    public com.example.smartpark.voice.model.VoiceSessionState state() {
        return stateMachine.state();
    }

    public boolean acceptsAudio() {
        return stateMachine.acceptsAudio();
    }

    /** Allocates the next per-session outbound frame sequence (0-based). */
    public long nextFrameSequence() {
        return frameSequence.getAndIncrement();
    }

    public String beginTurn() {
        turnInterrupted.set(false);
        receivedChunkCount = 0;
        audioValidator.reset();
        ringBuffer.release();
        pendingFinalText = null;
        currentTurnId = "turn-" + turnCounter.incrementAndGet();
        return currentTurnId;
    }

    public String currentTurnId() {
        return currentTurnId;
    }

    public void setPendingFinalText(String text) {
        pendingFinalText = text;
    }

    public String takePendingFinalText() {
        String text = pendingFinalText;
        pendingFinalText = null;
        return text;
    }

    /** Marks the active turn interrupted; later agent/TTS work for it is dropped. */
    public void markTurnInterrupted() {
        turnInterrupted.set(true);
    }

    public boolean isTurnInterrupted() {
        return turnInterrupted.get();
    }

    public void recordTtsChunk() {
        receivedChunkCount++;
    }

    public long receivedTtsChunkCount() {
        return receivedChunkCount;
    }

    public AudioFrameValidator audioValidator() {
        return audioValidator;
    }

    public VoiceAudioRingBuffer ringBuffer() {
        return ringBuffer;
    }

    public String sessionId() {
        return sessionId;
    }

    public UUID runId() {
        return runId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<DeadlineScheduler.Cancelable> deadlineHandles() {
        return List.copyOf(pendingDeadlines);
    }

    private final List<DeadlineScheduler.Cancelable> pendingDeadlines =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void trackDeadline(DeadlineScheduler.Cancelable handle) {
        pendingDeadlines.add(handle);
    }

    public void cancelTrackedDeadlines() {
        pendingDeadlines.forEach(DeadlineScheduler.Cancelable::cancel);
        pendingDeadlines.clear();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ringBuffer.release();
            serialExecutor.shutdownNow();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Drains the serial executor including tasks enqueued by earlier tasks
     * (nested chains); test determinism aid. No-op once the session is closed.
     */
    public void flush() {
        if (closed.get()) {
            return;
        }
        try {
            for (int round = 0; round < 10 && !closed.get(); round++) {
                serialExecutor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            if (closed.get()) {
                return; // session closed mid-flush: nothing left to drain
            }
            throw new IllegalStateException("failed to flush session executor", ex);
        }
    }
}
