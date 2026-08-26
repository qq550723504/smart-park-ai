package com.example.smartpark.voice;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import com.example.smartpark.voice.audio.AudioRejectionException;
import com.example.smartpark.voice.model.ClientControlFrame;
import com.example.smartpark.voice.model.VoiceClientControlType;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.model.VoiceServerFrame;
import com.example.smartpark.voice.model.VoiceSessionEvent;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceSessionState;
import com.example.smartpark.voice.model.VoiceSessionStateMachine;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Session lifecycle orchestrator. All work for one session funnels through its
 * serial executor; each turn owns an independent id, interruption flag and
 * deadline handles. CLOSE or network disconnect cancels ASR/TTS, releases the
 * audio buffer and completes the unified execution-event stream.
 */
public class VoiceSessionService {

    private static final Logger log = LoggerFactory.getLogger(VoiceSessionService.class);

    private final VoiceSessionStore store;
    private final StreamingAsrPort asrPort;
    private final StreamingTtsPort ttsPort;
    private final VoiceAnswerAgent answerAgent;
    private final ExecutionEventPublisher eventPublisher;
    private final VoiceDeadlines deadlines;
    private final DeadlineScheduler scheduler;
    private final java.util.concurrent.Executor agentExecutor;
    private final Map<String, VoiceFramePublisher> publishers = new ConcurrentHashMap<>();

    public VoiceSessionService(VoiceSessionStore store,
                               StreamingAsrPort asrPort,
                               StreamingTtsPort ttsPort,
                               VoiceAnswerAgent answerAgent,
                               ExecutionEventPublisher eventPublisher,
                               VoiceDeadlines deadlines,
                               DeadlineScheduler scheduler,
                               java.util.concurrent.Executor agentRunner) {
        this.store = store;
        this.asrPort = asrPort;
        this.ttsPort = ttsPort;
        this.answerAgent = answerAgent;
        this.eventPublisher = eventPublisher;
        this.deadlines = deadlines;
        this.scheduler = scheduler;
        this.agentExecutor = agentRunner != null ? agentRunner : Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "voice-answer-agent");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ---------------------------------------------------------------- lifecycle

    public record CreatedSession(String sessionId, String runId) {
    }

    /** Creates a session bound to a connection publisher; publishes the initial state. */
    public CreatedSession createSession(VoiceFramePublisher publisher) {
        VoiceSession session = store.create();
        if (publisher != null) {
            publishers.put(session.sessionId(), publisher);
        }
        publishState(session, null);
        return new CreatedSession(session.sessionId(), session.runId().toString());
    }

    /** Registers the publisher of an already-created session (WS attach). */
    public void attach(String sessionId, VoiceFramePublisher publisher) {
        findLiveSession(sessionId).ifPresent(session -> {
            publishers.put(sessionId, publisher);
            publishState(session, null);
        });
    }

    public Optional<VoiceSessionStore.Snapshot> describe(String sessionId) {
        return store.find(sessionId)
                .map(session -> new VoiceSessionStore.Snapshot(
                        sessionId, session.runId(), session.createdAt()));
    }

    public Optional<VoiceSessionState> stateOf(String sessionId) {
        return store.find(sessionId).map(VoiceSession::state);
    }

    /** CLOSE control: full cleanup and completion of the execution event stream. */
    public void closeSession(String sessionId) {
        store.find(sessionId).ifPresent(session -> session.execute(() -> closeNow(session)));
    }

    /** Network disconnect: same cleanup, tolerant when the session is gone. */
    public void handleDisconnect(String sessionId) {
        store.remove(sessionId).ifPresent(session -> closeNow(session));
        publishers.remove(sessionId);
    }

    private void closeNow(VoiceSession session) {
        String turnId = session.currentTurnId();
        if (turnId != null && !session.isTurnInterrupted()) {
            session.markTurnInterrupted();
            cancelTurnPorts(session.sessionId(), turnId);
        }
        session.cancelTrackedDeadlines();
        safeApply(session, VoiceSessionEvent.SESSION_CLOSED);
        publishState(session, VoiceSessionEvent.SESSION_CLOSED);
        session.ringBuffer().release();
        session.close();
        store.remove(session.sessionId());
        VoiceFramePublisher publisher = publishers.remove(session.sessionId());
        if (publisher != null) {
            publisher.close();
        }
    }

    // ---------------------------------------------------------------- input paths

    public void handleBinaryAudio(String sessionId, byte[] pcm) {
        findLiveSession(sessionId).ifPresent(session -> session.execute(() -> {
            if (!session.acceptsAudio()) {
                reject(session, VoiceErrorCode.UNSUPPORTED_STATE, "当前状态不接受音频输入");
                return;
            }
            try {
                var chunk = session.audioValidator().validate(pcm, session.state());
                session.ringBuffer().append(chunk);
                String turnId = session.currentTurnId();
                if (turnId != null) {
                    asrPort.send(session.sessionId(), turnId, chunk.data());
                }
            } catch (AudioRejectionException rejection) {
                log.debug("voice audio rejected in {}: {}", sessionId, rejection.reason());
                reject(session, VoiceErrorCode.AUDIO_REJECTED,
                        "音频帧被拒绝：" + rejection.reason());
            }
        }));
    }

    public void handleControl(String sessionId, ClientControlFrame control) {
        findLiveSession(sessionId).ifPresentOrElse(
                session -> session.execute(() -> dispatchControl(session, control)),
                () -> { /* unknown session: WS layer closes; REST layer reports */ });
    }

    private void dispatchControl(VoiceSession session, ClientControlFrame control) {
        VoiceClientControlType type = control.type();
        switch (type) {
            case START_INPUT -> onStartInput(session);
            case COMMIT_INPUT -> onCommitInput(session);
            case INTERRUPT_OUTPUT -> onInterruptOutput(session);
            case CLOSE_SESSION -> closeNow(session);
        }
    }

    private void onStartInput(VoiceSession session) {
        // ERROR 是可重试状态：允许新的输入尝试（beginTurn 会重置中断标记）。
        boolean retryFromError = session.state() == VoiceSessionState.ERROR;
        if (!retryFromError && session.isTurnInterrupted()) {
            reject(session, VoiceErrorCode.UNSUPPORTED_STATE, "上一轮正在结束，请稍候");
            return;
        }
        VoiceSessionState current = session.state();
        if (current == VoiceSessionState.SPEAKING
                || current == VoiceSessionState.ANSWER_STREAMING
                || current == VoiceSessionState.REASONING
                || current == VoiceSessionState.TOOL_CALLING) {
            interruptCurrentOutput(session);
        }
        if (!session.tryApply(VoiceSessionEvent.START_INPUT)) {
            reject(session, VoiceErrorCode.UNSUPPORTED_STATE, "当前状态无法开始新的语音输入");
            return;
        }
        publishState(session, VoiceSessionEvent.START_INPUT);

        String turnId = session.beginTurn();
        asrPort.start(session.sessionId(), turnId, asrListener(session));
        trackDeadline(session, deadlines.maxInputDuration(),
                () -> timeoutInput(session));
    }

    private void onCommitInput(VoiceSession session) {
        String turnId = session.currentTurnId();
        if (turnId == null || !session.tryApply(VoiceSessionEvent.INPUT_COMMITTED)) {
            reject(session, VoiceErrorCode.UNSUPPORTED_STATE, "重复提交或当前状态不允许提交");
            return;
        }
        publishState(session, VoiceSessionEvent.INPUT_COMMITTED);
        asrPort.commit(session.sessionId(), turnId);
        trackDeadline(session, deadlines.maxAgentDuration(), () -> timeoutAgent(session));
    }

    private void onInterruptOutput(VoiceSession session) {
        interruptCurrentOutput(session);
    }

    /** Mic-click path: stop TTS/agent output, then land idle so listening can restart. */
    private void interruptCurrentOutput(VoiceSession session) {
        String turnId = session.currentTurnId();
        if (turnId == null || session.isTurnInterrupted()) {
            return;
        }
        session.markTurnInterrupted();
        cancelTurnPorts(session.sessionId(), turnId);
        session.cancelTrackedDeadlines();
        if (safeApply(session, VoiceSessionEvent.OUTPUT_INTERRUPTED)) {
            session.ringBuffer().release();
            publishState(session, VoiceSessionEvent.OUTPUT_INTERRUPTED);
        }
    }

    private void cancelTurnPorts(String sessionId, String turnId) {
        try {
            asrPort.cancel(sessionId, turnId);
        } catch (RuntimeException ex) {
            log.debug("asr cancel ignored for {}: {}", sessionId, ex.getClass().getSimpleName());
        }
        try {
            ttsPort.cancel(sessionId, turnId);
        } catch (RuntimeException ex) {
            log.debug("tts cancel ignored for {}: {}", sessionId, ex.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- timeouts

    private void timeoutInput(VoiceSession session) {
        session.execute(() -> {
            if (session.state() != VoiceSessionState.LISTENING || session.isTurnInterrupted()) {
                return;
            }
            failTurn(session, VoiceErrorCode.TIMEOUT, "输入超时，请重新开始");
        });
    }

    private void timeoutAgent(VoiceSession session) {
        session.execute(() -> {
            VoiceSessionState state = session.state();
            boolean inAgentPhase = state == VoiceSessionState.ASR_FINALIZED
                    || state == VoiceSessionState.REASONING
                    || state == VoiceSessionState.TOOL_CALLING
                    || state == VoiceSessionState.ANSWER_STREAMING;
            if (!inAgentPhase || session.isTurnInterrupted()) {
                return;
            }
            session.markTurnInterrupted();
            failTurn(session, VoiceErrorCode.TIMEOUT, "回答生成超时");
        });
    }

    private void timeoutTtsFirstChunk(VoiceSession session) {
        session.execute(() -> {
            if (session.state() != VoiceSessionState.SPEAKING
                    || session.receivedTtsChunkCount() > 0
                    || session.isTurnInterrupted()) {
                return;
            }
            session.markTurnInterrupted();
            String turnId = session.currentTurnId();
            if (turnId != null) {
                ttsPort.cancel(session.sessionId(), turnId);
            }
            failTurn(session, VoiceErrorCode.TIMEOUT, "语音合成超时");
        });
    }

    // ---------------------------------------------------------------- ASR callbacks

    private StreamingAsrPort.Listener asrListener(VoiceSession session) {
        String sessionId = session.sessionId();
        return new StreamingAsrPort.Listener() {

            @Override
            public void onPartial(String sid, String turnId, String text) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    publish(new com.example.smartpark.voice.model.AsrPartialFrame(
                            sessionId, messageId(sid), session.nextFrameSequence(), text));
                });
            }

            @Override
            public void onFinal(String sid, String turnId, String text) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    if (text.isBlank()) {
                        failTurn(session, VoiceErrorCode.AUDIO_REJECTED, "未识别到语音内容，请重试");
                        return;
                    }
                    publish(new com.example.smartpark.voice.model.AsrFinalFrame(
                            sessionId, messageId(sid), session.nextFrameSequence(), text));
                    startAnswerPhase(session, turnId, text);
                });
            }

            @Override
            public void onError(String sid, String turnId, VoiceErrorCode code) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    failTurn(session, code, userMessageFor(code));
                });
            }

            @Override
            public void onClosed(String sid, String turnId) {
                // Terminal transcript/error handling happens in onFinal/onError.
            }

            private boolean stale(VoiceSession s, String turnId) {
                return s.isClosed()
                        || !turnId.equals(s.currentTurnId())
                        || s.isTurnInterrupted();
            }
        };
    }

    // ---------------------------------------------------------------- answer + TTS

    /** Runs the evidence-constrained agent off the serial executor so mic clicks stay responsive. */
    private void startAnswerPhase(VoiceSession session, String turnId, String question) {
        safeApply(session, VoiceSessionEvent.REASONING_STARTED);
        publishState(session, VoiceSessionEvent.REASONING_STARTED);
        agentExecutor.execute(() -> {
            final String sid = session.sessionId();
            VoiceAnswerAgent.Listener listener = new VoiceAnswerAgent.Listener() {

                @Override
                public void onToolStarted(String toolName, String argumentSummary) {
                    session.execute(() -> {
                        if (stale(session, turnId)) {
                            return;
                        }
                        safeApply(session, VoiceSessionEvent.TOOL_CALL_STARTED);
                        publishState(session, VoiceSessionEvent.TOOL_CALL_STARTED);
                        publish(new com.example.smartpark.voice.model.ToolEventFrame(
                                sid, messageId(sid), session.nextFrameSequence(),
                                toolName, "STARTED", argumentSummary));
                    });
                }

                @Override
                public void onToolCompleted(String toolName, boolean success) {
                    session.execute(() -> {
                        if (stale(session, turnId)) {
                            return;
                        }
                        safeApply(session, VoiceSessionEvent.TOOL_CALL_COMPLETED);
                        publishState(session, VoiceSessionEvent.TOOL_CALL_COMPLETED);
                        publish(new com.example.smartpark.voice.model.ToolEventFrame(
                                sid, messageId(sid), session.nextFrameSequence(),
                                toolName, "COMPLETED", success ? "ok" : "failed"));
                    });
                }

                @Override
                public void onTextDelta(String delta) {
                    session.execute(() -> {
                        if (stale(session, turnId)) {
                            return;
                        }
                        if (session.tryApply(VoiceSessionEvent.ANSWER_STREAM_STARTED)) {
                            publishState(session, VoiceSessionEvent.ANSWER_STREAM_STARTED);
                        }
                        publish(new com.example.smartpark.voice.model.AnswerDeltaFrame(
                                sid, messageId(sid), session.nextFrameSequence(), delta));
                    });
                }
            };

            try {
                VoiceAnswer answer = answerAgent.answer(session.sessionId(), turnId, question, listener);
                session.execute(() -> deliverAnswer(session, turnId, answer.text()));
            } catch (RuntimeException ex) {
                log.warn("voice answer failed in {}: {}", session.sessionId(), ex.getClass().getSimpleName());
                session.execute(() -> {
                    if (!stale(session, turnId)) {
                        failTurn(session, VoiceErrorCode.ANSWER_VALIDATION_FAILED,
                                "回答校验未通过，本轮结束");
                    }
                });
            }
        });
    }

    /** Only validator-approved text reaches this point; TTS begins now. */
    private void deliverAnswer(VoiceSession session, String turnId, String validatedText) {
        if (session.isClosed() || !turnId.equals(session.currentTurnId())
                || session.isTurnInterrupted()) {
            return;
        }
        safeApply(session, VoiceSessionEvent.ANSWER_COMPLETED);
        publishState(session, VoiceSessionEvent.ANSWER_COMPLETED);
        trackDeadline(session, deadlines.ttsFirstChunkTimeout(), () -> timeoutTtsFirstChunk(session));
        final String sid = session.sessionId();
        ttsPort.start(session.sessionId(), turnId, List.of(validatedText), ttsListener(session, sid, turnId));
    }

    private StreamingTtsPort.Listener ttsListener(VoiceSession session, String sessionId, String turnId) {
        return new StreamingTtsPort.Listener() {

            @Override
            public void onAudioChunk(String sid, String tId, int chunkSequence, byte[] audio) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    session.recordTtsChunk();
                    session.cancelTrackedDeadlines();
                    publish(new com.example.smartpark.voice.model.AudioChunkFrame(
                            sessionId, messageId(sid), session.nextFrameSequence(),
                            chunkSequence, audio.length));
                    VoiceFramePublisher raw = publishers.get(sid);
                    if (raw != null) {
                        raw.publishAudioChunk(chunkSequence, audio.clone());
                    }
                });
            }

            @Override
            public void onError(String sid, String tId, VoiceErrorCode code) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    failTurn(session, code, userMessageFor(code));
                });
            }

            @Override
            public void onCompleted(String sid, String tId) {
                session.execute(() -> {
                    if (stale(session, turnId)) {
                        return;
                    }
                    finishTurn(session);
                });
            }

            @Override
            public void onInterrupted(String sid, String tId) {
                // Interruption is driven by START_INPUT/CLOSE paths; nothing to do here.
            }

            private boolean stale(VoiceSession s, String expectedTurn) {
                return s.isClosed()
                        || !expectedTurn.equals(s.currentTurnId())
                        || s.isTurnInterrupted();
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private void finishTurn(VoiceSession session) {
        session.cancelTrackedDeadlines();
        if (safeApply(session, VoiceSessionEvent.TURN_COMPLETED)) {
            session.ringBuffer().release();
            publishState(session, VoiceSessionEvent.TURN_COMPLETED);
        }
    }

    /** Explicit failure: ERROR state (retryable), error frame, buffer released, ports stopped. */
    private void failTurn(VoiceSession session, VoiceErrorCode code, String message) {
        String turnId = session.currentTurnId();
        if (turnId != null) {
            cancelTurnPorts(session.sessionId(), turnId);
        }
        session.markTurnInterrupted();
        session.cancelTrackedDeadlines();
        safeApply(session, VoiceSessionEvent.ERROR_OCCURRED);
        publishState(session, VoiceSessionEvent.ERROR_OCCURRED);
        reject(session, code, message);
        session.ringBuffer().release();
    }

    private void reject(VoiceSession session, VoiceErrorCode code, String message) {
        publish(new com.example.smartpark.voice.model.ErrorFrame(
                session.sessionId(), messageId(session.sessionId()),
                session.nextFrameSequence(), code, message));
    }

    private void publishState(VoiceSession session, VoiceSessionEvent cause) {
        publish(new com.example.smartpark.voice.model.SessionStateFrame(
                session.sessionId(), messageId(session.sessionId()), session.nextFrameSequence(),
                session.state(), session.currentTurnId()));
        if (cause != null) {
            publishExecutionEvent(session, cause);
        }
    }

    /** Synchronously mirrors every accepted transition onto the unified event stream. */
    private void publishExecutionEvent(VoiceSession session, VoiceSessionEvent cause) {
        try {
            eventPublisher.publish(new ExecutionEvent(
                    java.util.UUID.randomUUID(), session.runId(), 0, Instant.now(),
                    ExecutionScenario.VOICE, "voice-session", stageFor(cause),
                    typeFor(cause), statusFor(cause),
                    "voice session " + cause + " -> " + session.state(), null));
        } catch (RuntimeException ex) {
            log.debug("execution event publish skipped: {}", ex.getClass().getSimpleName());
        }
    }

    private static ExecutionStage stageFor(VoiceSessionEvent cause) {
        return switch (cause) {
            case START_INPUT -> ExecutionStage.INPUT_CAPTURE;
            case INPUT_COMMITTED -> ExecutionStage.UNDERSTANDING;
            case REASONING_STARTED -> ExecutionStage.PLANNING;
            case TOOL_CALL_STARTED, TOOL_CALL_COMPLETED -> ExecutionStage.TOOL_EXECUTION;
            case ANSWER_STREAM_STARTED, ANSWER_COMPLETED -> ExecutionStage.ANALYSIS;
            case TURN_COMPLETED, OUTPUT_INTERRUPTED, SESSION_CLOSED, ERROR_OCCURRED ->
                    ExecutionStage.INPUT_CAPTURE;
        };
    }

    private static ExecutionEventType typeFor(VoiceSessionEvent cause) {
        return switch (cause) {
            case START_INPUT -> ExecutionEventType.RUN_STARTED;
            case INPUT_COMMITTED -> ExecutionEventType.TEXT_COMPLETED;
            case REASONING_STARTED -> ExecutionEventType.NODE_STARTED;
            case TOOL_CALL_STARTED -> ExecutionEventType.TOOL_CALL_STARTED;
            case TOOL_CALL_COMPLETED -> ExecutionEventType.TOOL_CALL_COMPLETED;
            case ANSWER_STREAM_STARTED -> ExecutionEventType.TEXT_DELTA;
            case ANSWER_COMPLETED -> ExecutionEventType.AUDIO_STARTED;
            case TURN_COMPLETED -> ExecutionEventType.AUDIO_COMPLETED;
            case OUTPUT_INTERRUPTED -> ExecutionEventType.INTERRUPTED;
            case ERROR_OCCURRED -> ExecutionEventType.FAILED;
            case SESSION_CLOSED -> ExecutionEventType.COMPLETED;
        };
    }

    private static ExecutionStatus statusFor(VoiceSessionEvent cause) {
        return switch (cause) {
            case TURN_COMPLETED, SESSION_CLOSED -> ExecutionStatus.SUCCEEDED;
            case ERROR_OCCURRED -> ExecutionStatus.FAILED;
            case OUTPUT_INTERRUPTED -> ExecutionStatus.INTERRUPTED;
            default -> ExecutionStatus.RUNNING;
        };
    }

    private static boolean stale(VoiceSession session, String turnId) {
        return session.isClosed()
                || !turnId.equals(session.currentTurnId())
                || session.isTurnInterrupted();
    }

    private boolean safeApply(VoiceSession session, VoiceSessionEvent event) {
        return session.apply(event) != null;
    }

    private void trackDeadline(VoiceSession session, Duration budget, Runnable onExpiry) {
        DeadlineScheduler.Cancelable handle =
                scheduler.schedule(() -> onExpiry.run(), budget);
        session.trackDeadline(handle);
    }

    private VoiceFramePublisher publishTarget(String sessionId) {
        return publishers.getOrDefault(sessionId, NOOP_PUBLISHER);
    }

    private void publish(VoiceServerFrame frame) {
        VoiceFramePublisher publisher = publishTarget(frame.sessionId());
        if (publisher != null) {
            publisher.publish(frame);
        }
    }

    private long sequenceCounterSeed = 0;

    private synchronized String messageId(String sessionId) {
        return sessionId + "-m" + (++sequenceCounterSeed);
    }

    private static String userMessageFor(VoiceErrorCode code) {
        return switch (code) {
            case TIMEOUT -> "处理超时，请重试";
            case PROVIDER_FAILURE -> "语音服务暂时不可用，请重试";
            default -> "处理失败，请重试";
        };
    }

    private Optional<VoiceSession> findLiveSession(String sessionId) {
        return store.find(sessionId).filter(session -> !session.isClosed());
    }

    private static final VoiceFramePublisher NOOP_PUBLISHER = new VoiceFramePublisher() {
        @Override
        public void publish(com.example.smartpark.voice.model.VoiceServerFrame frame) {
        }

        @Override
        public void publishAudioChunk(int chunkSequence, byte[] pcm) {
        }
    };
}
