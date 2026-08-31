package com.example.smartpark.showcase;

import com.example.smartpark.voice.VoiceAnswerAgent;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(prefix = "smartpark.voice", name = "enabled", havingValue = "true")
public final class VoiceAssistantPreflightProbe implements ShowcasePreflightProbe {

    private static final String QUESTION = "DEV-ENERGY-001 现在用了多少电？";
    private static final String ID_PREFIX = "showcase-preflight-";
    private static final int SILENCE_FRAMES = 50;
    private static final int PCM_BYTES_PER_FRAME = 640;

    private final StreamingAsrPort asr;
    private final VoiceAnswerAgent agent;
    private final StreamingTtsPort tts;

    public VoiceAssistantPreflightProbe(
            StreamingAsrPort asr, VoiceAnswerAgent agent, StreamingTtsPort tts) {
        this.asr = Objects.requireNonNull(asr, "asr");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.tts = Objects.requireNonNull(tts, "tts");
    }

    @Override
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.VOICE_ASSISTANT;
    }

    @Override
    public ShowcaseProbeResult probe() {
        String sessionId = newIdentifier();
        String turnId = newIdentifier();
        Thread probeThread = Thread.currentThread();
        boolean asrStarted = false;
        boolean ttsStarted = false;

        try {
            if (probeThread.isInterrupted()) {
                return ShowcaseProbeResult.FAILED;
            }
            CountDownLatch asrClosed = new CountDownLatch(1);
            AtomicReference<AsrTerminalState> asrTerminal =
                    new AtomicReference<>(AsrTerminalState.PENDING);
            asrStarted = true;
            asr.start(sessionId, turnId, new StreamingAsrPort.Listener() {
                @Override
                public void onPartial(String callbackSessionId, String callbackTurnId, String text) {
                    if (!matches(sessionId, turnId, callbackSessionId, callbackTurnId)) {
                        return;
                    }
                    // Silence proves a clean provider turn, not transcript accuracy.
                }

                @Override
                public void onFinal(String callbackSessionId, String callbackTurnId, String text) {
                    if (!matches(sessionId, turnId, callbackSessionId, callbackTurnId)) {
                        return;
                    }
                    // Transcripts are intentionally neither inspected nor retained.
                }

                @Override
                public void onError(String callbackSessionId, String callbackTurnId,
                                    VoiceErrorCode code) {
                    if (matches(sessionId, turnId, callbackSessionId, callbackTurnId)) {
                        asrTerminal.updateAndGet(VoiceAssistantPreflightProbe::recordAsrError);
                    }
                }

                @Override
                public void onClosed(String callbackSessionId, String callbackTurnId) {
                    if (matches(sessionId, turnId, callbackSessionId, callbackTurnId)) {
                        asrTerminal.updateAndGet(VoiceAssistantPreflightProbe::recordAsrClose);
                        asrClosed.countDown();
                    }
                }
            });
            for (int frame = 0; frame < SILENCE_FRAMES; frame++) {
                asr.send(sessionId, turnId, new byte[PCM_BYTES_PER_FRAME]);
            }
            asr.commit(sessionId, turnId);
            asrClosed.await();
            if (asrTerminal.get() != AsrTerminalState.CLOSED
                    || probeThread.isInterrupted()) {
                return ShowcaseProbeResult.FAILED;
            }

            AtomicBoolean successfulToolCompletion = new AtomicBoolean();
            VoiceAnswer answer = agent.answer(sessionId, turnId, QUESTION,
                    new VoiceAnswerAgent.Listener() {
                        @Override
                        public void onToolStarted(String toolName, String argumentSummary) {
                            // Tool details are deliberately not retained by preflight.
                        }

                        @Override
                        public void onToolCompleted(String toolName, boolean success) {
                            if (success) {
                                successfulToolCompletion.set(true);
                            }
                        }

                        @Override
                        public void onTextDelta(String delta) {
                            // Model output is deliberately not retained by preflight.
                        }
                    }, probeThread::isInterrupted);
            if (probeThread.isInterrupted()
                    || !isValid(answer, successfulToolCompletion.get())) {
                return ShowcaseProbeResult.FAILED;
            }

            CountDownLatch ttsTerminal = new CountDownLatch(1);
            AtomicBoolean receivedAudio = new AtomicBoolean();
            AtomicReference<TtsTerminalState> ttsTerminalState =
                    new AtomicReference<>(TtsTerminalState.PENDING);
            if (probeThread.isInterrupted()) {
                return ShowcaseProbeResult.FAILED;
            }
            ttsStarted = true;
            tts.start(sessionId, turnId, List.of(answer.text()), new StreamingTtsPort.Listener() {
                @Override
                public void onAudioChunk(String callbackSessionId, String callbackTurnId,
                                         int chunkSequence, byte[] audio) {
                    if (audio == null) {
                        return;
                    }
                    try {
                        if (matches(sessionId, turnId, callbackSessionId, callbackTurnId)
                                && audio.length > 0) {
                            receivedAudio.set(true);
                        }
                    }
                    finally {
                        Arrays.fill(audio, (byte) 0);
                    }
                }

                @Override
                public void onError(String callbackSessionId, String callbackTurnId,
                                    VoiceErrorCode code) {
                    recordTtsTerminal(callbackSessionId, callbackTurnId,
                            TtsTerminalState.ERROR);
                }

                @Override
                public void onCompleted(String callbackSessionId, String callbackTurnId) {
                    recordTtsTerminal(callbackSessionId, callbackTurnId,
                            TtsTerminalState.COMPLETED);
                }

                @Override
                public void onInterrupted(String callbackSessionId, String callbackTurnId) {
                    recordTtsTerminal(callbackSessionId, callbackTurnId,
                            TtsTerminalState.INTERRUPTED);
                }

                private void recordTtsTerminal(String callbackSessionId, String callbackTurnId,
                                               TtsTerminalState event) {
                    if (matches(sessionId, turnId, callbackSessionId, callbackTurnId)) {
                        ttsTerminalState.updateAndGet(current ->
                                current == TtsTerminalState.PENDING
                                        ? event
                                        : TtsTerminalState.INVALID);
                        ttsTerminal.countDown();
                    }
                }
            });
            ttsTerminal.await();
            return receivedAudio.get()
                    && ttsTerminalState.get() == TtsTerminalState.COMPLETED
                    && !probeThread.isInterrupted()
                    ? ShowcaseProbeResult.PASSED
                    : ShowcaseProbeResult.FAILED;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ShowcaseProbeResult.FAILED;
        }
        catch (RuntimeException failure) {
            return ShowcaseProbeResult.FAILED;
        }
        finally {
            if (ttsStarted) {
                cancelTts(sessionId, turnId);
            }
            if (asrStarted) {
                cancelAsr(sessionId, turnId);
            }
        }
    }

    private static boolean isValid(VoiceAnswer answer, boolean successfulToolCompletion) {
        return answer != null
                && !answer.text().isBlank()
                && !answer.evidenceRefs().isEmpty()
                && !answer.toolCalls().isEmpty()
                && successfulToolCompletion;
    }

    private static boolean matches(String sessionId, String turnId,
                                   String callbackSessionId, String callbackTurnId) {
        return sessionId.equals(callbackSessionId) && turnId.equals(callbackTurnId);
    }

    private static AsrTerminalState recordAsrError(AsrTerminalState current) {
        return switch (current) {
            case PENDING -> AsrTerminalState.ERROR;
            case ERROR -> AsrTerminalState.ERROR;
            case CLOSED, INVALID -> AsrTerminalState.INVALID;
        };
    }

    private static AsrTerminalState recordAsrClose(AsrTerminalState current) {
        return switch (current) {
            case PENDING -> AsrTerminalState.CLOSED;
            case ERROR -> AsrTerminalState.ERROR;
            case CLOSED, INVALID -> AsrTerminalState.INVALID;
        };
    }

    private void cancelAsr(String sessionId, String turnId) {
        try {
            asr.cancel(sessionId, turnId);
        }
        catch (RuntimeException ignored) {
            // Cleanup remains best-effort and must not expose provider details.
        }
    }

    private void cancelTts(String sessionId, String turnId) {
        try {
            tts.cancel(sessionId, turnId);
        }
        catch (RuntimeException ignored) {
            // Continue to ASR cleanup even if the provider rejects TTS cancellation.
        }
    }

    private static String newIdentifier() {
        return ID_PREFIX + UUID.randomUUID();
    }

    private enum AsrTerminalState {
        PENDING,
        CLOSED,
        ERROR,
        INVALID
    }

    private enum TtsTerminalState {
        PENDING,
        COMPLETED,
        ERROR,
        INTERRUPTED,
        INVALID
    }
}
