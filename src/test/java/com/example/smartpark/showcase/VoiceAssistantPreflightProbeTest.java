package com.example.smartpark.showcase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.smartpark.voice.VoiceAnswerAgent;
import com.example.smartpark.voice.model.ToolCallRecord;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceAssistantPreflightProbeTest {

    private static final String QUESTION = "DEV-ENERGY-001 现在用了多少电？";

    @Test
    void passesACompleteProviderChainWithFreshSilenceAndGuaranteedCleanup() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        byte[] providerAudio = new byte[] {1, 2};
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, providerAudio);
        VoiceAnswerAgent agent = successfulAgent(validAnswer());

        VoiceAssistantPreflightProbe probe = new VoiceAssistantPreflightProbe(asr, agent, tts);
        ShowcaseProbeResult result = probe.probe();

        assertThat(probe.scenarioId()).isEqualTo(ShowcaseScenarioId.VOICE_ASSISTANT);
        assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
        assertThat(asr.committed).isTrue();
        assertThat(asr.frames).hasSize(50)
                .allSatisfy(frame -> {
                    assertThat(frame).hasSize(640);
                    assertThat(frame).containsOnly((byte) 0);
                });
        assertThat(asr.frames.stream().collect(
                () -> new IdentityHashMap<byte[], Boolean>(),
                (frames, frame) -> frames.put(frame, Boolean.TRUE),
                IdentityHashMap::putAll)).hasSize(50);
        assertThat(asr.sessionId).startsWith("showcase-preflight-");
        assertThat(asr.turnId).startsWith("showcase-preflight-").isNotEqualTo(asr.sessionId);
        assertThat(tts.sessionId).isEqualTo(asr.sessionId);
        assertThat(tts.turnId).isEqualTo(asr.turnId);
        assertThat(tts.textSegments).containsExactly(validAnswer().text());
        assertThat(providerAudio).containsOnly((byte) 0);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.cancelAttempts).isOne();
        verify(agent).answer(eq(asr.sessionId), eq(asr.turnId), eq(QUESTION),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class));
    }

    @Test
    void observesPassedProviderChainWithOnlyStageOutcomeAndCounters() {
        ListAppender<ILoggingEvent> appender = captureProbeLogs();
        try {
            ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
            ScriptedTtsPort tts = new ScriptedTtsPort(
                    TtsBehavior.COMPLETED, new byte[] {1, 2});

            ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                    asr, successfulAgent(validAnswer()), tts).probe();

            assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("voice preflight stage=TTS outcome=PASSED "
                            + "asrFinalCount=0 ttsAudioChunkCount=1")
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(QUESTION, validAnswer().text(),
                                    "paraformer-realtime-v2", "cosyvoice-v2",
                                    "longxiaochun_v2", "sk-sensitive"));
        }
        finally {
            releaseProbeLogs(appender);
        }
    }

    @Test
    void observesFailedAsrBoundaryWithoutProviderOrTranscriptDetail() {
        ListAppender<ILoggingEvent> appender = captureProbeLogs();
        try {
            ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                    new ScriptedAsrPort(AsrBehavior.ERROR_THEN_CLOSED),
                    mock(VoiceAnswerAgent.class),
                    new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1})).probe();

            assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("voice preflight stage=ASR outcome=FAILED "
                            + "asrFinalCount=0 ttsAudioChunkCount=0");
        }
        finally {
            releaseProbeLogs(appender);
        }
    }

    @Test
    void failsAnAsrErrorEvenWhenTheTurnThenCloses() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.ERROR_THEN_CLOSED);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
        verify(agent, never()).answer(anyString(), anyString(), anyString(),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class));
    }

    @Test
    void failsAndSkipsTtsWhenTheAgentThrows() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        stubAgent(agent, invocation -> {
            throw new IllegalStateException("provider output must stay private");
        });
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void rejectsAgentAnswersMissingAnyRequiredEvidenceBoundary(VoiceAnswer answer) {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(answer), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
    }

    @Test
    void rejectsAnAnswerWithoutASuccessfulToolCompletionCallback() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        stubAgent(agent, invocation -> {
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolStarted("lookupEnergyConsumption", "meterId=DEV-ENERGY-001");
                    listener.onToolCompleted("lookupEnergyConsumption", false);
                    return validAnswer();
                });

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
    }

    @Test
    void rejectsTtsCompletionWithOnlyEmptyChunksAndCancelsTheTurn() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[0]);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.cancelAttempts).isOne();
    }

    @ParameterizedTest
    @EnumSource(value = TtsBehavior.class, names = {"ERROR", "INTERRUPTED"})
    void rejectsEveryAbnormalTtsTerminalAndWipesDeliveredAudio(TtsBehavior behavior) {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        byte[] providerAudio = new byte[] {7, 8};
        ScriptedTtsPort tts = new ScriptedTtsPort(behavior, providerAudio);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(providerAudio).containsOnly((byte) 0);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.cancelAttempts).isOne();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void interruptionWhileAwaitingAsrRestoresTheFlagAndSkipsLaterStages()
            throws InterruptedException {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.BLOCK);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});
        VoiceAssistantPreflightProbe probe = new VoiceAssistantPreflightProbe(asr, agent, tts);
        ProbeThread run = new ProbeThread(probe);

        run.start();
        asr.commitLatch.await();
        run.interruptAndJoin();

        assertThat(run.result.get()).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(run.interruptedAfterProbe).isTrue();
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
        verify(agent, never()).answer(anyString(), anyString(), anyString(),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class));
    }

    @Test
    void interruptionDuringAgentUsesCooperativeSignalAndSkipsTts() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        when(agent.answer(anyString(), anyString(), eq(QUESTION),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    BooleanSupplier cancelled = invocation.getArgument(4);
                    assertThat(cancelled.getAsBoolean()).isFalse();
                    Thread.currentThread().interrupt();
                    assertThat(cancelled.getAsBoolean()).isTrue();
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolCompleted("lookupEnergyConsumption", true);
                    return validAnswer();
                });

        try {
            ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

            assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(asr.cancelAttempts).isOne();
            assertThat(tts.started).isFalse();
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void interruptionWhileAwaitingTtsRestoresTheFlagAndCancelsEveryStartedTurn()
            throws InterruptedException {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.BLOCK);
        VoiceAssistantPreflightProbe probe = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts);
        ProbeThread run = new ProbeThread(probe);

        run.start();
        tts.startedLatch.await();
        run.interruptAndJoin();

        assertThat(run.result.get()).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(run.interruptedAfterProbe).isTrue();
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.cancelAttempts).isOne();
    }

    @ParameterizedTest
    @EnumSource(value = AsrBehavior.class,
            names = {"START_THROWS", "SEND_THROWS", "COMMIT_THROWS"})
    void exceptionsAfterAttemptedAsrStartupFailAndStillCancel(AsrBehavior behavior) {
        ScriptedAsrPort asr = new ScriptedAsrPort(behavior);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.started).isTrue();
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.started).isFalse();
        verify(agent, never()).answer(anyString(), anyString(), anyString(),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class));
    }

    @Test
    void ttsSynthesisExceptionAfterAttemptedStartFailsAndCancelsBothTurns() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.START_THROWS);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(tts.started).isTrue();
        assertThat(tts.cancelAttempts).isOne();
        assertThat(asr.cancelAttempts).isOne();
    }

    @Test
    void ignoresMismatchedAsrErrorsAndAcceptsTheMatchingCleanClose() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.MISMATCH_ERROR_THEN_CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void mismatchedAsrCloseDoesNotReleaseTheWait() throws InterruptedException {
        ExternallyClosedAsrPort asr = new ExternallyClosedAsrPort();
        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);
        VoiceAnswerAgent agent = blockingSuccessfulAgent(agentStarted, releaseAgent);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});
        ProbeThread run = new ProbeThread(new VoiceAssistantPreflightProbe(asr, agent, tts));

        try {
            run.start();
            asr.mismatchedCloseSent.await();
            awaitWaiting(run.thread);
            assertThat(agentStarted.getCount()).isOne();

            asr.emitMatchingClose();
            agentStarted.await();
            releaseAgent.countDown();
            run.join();

            assertThat(run.result.get()).isEqualTo(ShowcaseProbeResult.PASSED);
        }
        finally {
            asr.emitMatchingClose();
            releaseAgent.countDown();
            run.interruptAndJoin();
        }
    }

    @Test
    void mismatchedTtsAudioIsWipedButDoesNotCountAsProviderOutput() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        byte[] mismatchedAudio = new byte[] {4, 5};
        ScriptedTtsPort tts = new ScriptedTtsPort(
                TtsBehavior.MISMATCH_AUDIO_THEN_COMPLETED, mismatchedAudio);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(mismatchedAudio).containsOnly((byte) 0);
    }

    @Test
    void ignoresMismatchedTtsCallbacksWhileWipingEveryAudioBuffer() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        byte[] mismatchedAudio = new byte[] {4, 5};
        byte[] matchingAudio = new byte[] {6, 7};
        ScriptedTtsPort tts = new ScriptedTtsPort(
                TtsBehavior.MISMATCH_CALLBACKS_THEN_MATCHING_SUCCESS,
                mismatchedAudio, matchingAudio);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
        assertThat(mismatchedAudio).containsOnly((byte) 0);
        assertThat(matchingAudio).containsOnly((byte) 0);
    }

    @Test
    void rejectsConflictingAsrTerminalCallbacks() {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CONFLICT_CLOSE_THEN_ERROR);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(tts.started).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TtsBehavior.class,
            names = {"CONFLICT_COMPLETED_THEN_ERROR", "CONFLICT_ERROR_THEN_COMPLETED"})
    void rejectsConflictingTtsTerminalCallbacksAndWipesAudio(TtsBehavior behavior) {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        byte[] audio = new byte[] {8, 9};
        ScriptedTtsPort tts = new ScriptedTtsPort(behavior, audio);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(audio).containsOnly((byte) 0);
    }

    @ParameterizedTest
    @EnumSource(CancelFailure.class)
    void attemptsBothCancellationsEvenWhenOneProviderThrows(CancelFailure failure) {
        ScriptedAsrPort asr = new ScriptedAsrPort(AsrBehavior.CLOSED);
        ScriptedTtsPort tts = new ScriptedTtsPort(TtsBehavior.COMPLETED, new byte[] {1});
        asr.throwOnCancel = failure == CancelFailure.ASR;
        tts.throwOnCancel = failure == CancelFailure.TTS;

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
        assertThat(asr.cancelAttempts).isOne();
        assertThat(tts.cancelAttempts).isOne();
    }

    private static Stream<VoiceAnswer> invalidAnswers() {
        ToolCallRecord call = validToolCall();
        return Stream.of(
                new VoiceAnswer(" ", List.of("DEV-ENERGY-001"), List.of(call)),
                new VoiceAnswer("当前用电正常", List.of(), List.of(call)),
                new VoiceAnswer("当前用电正常", List.of("DEV-ENERGY-001"), List.of()));
    }

    private static ListAppender<ILoggingEvent> captureProbeLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(VoiceAssistantPreflightProbe.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void releaseProbeLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(VoiceAssistantPreflightProbe.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static VoiceAnswer validAnswer() {
        return new VoiceAnswer(
                "当前用电正常",
                List.of("DEV-ENERGY-001"),
                List.of(validToolCall()));
    }

    private static ToolCallRecord validToolCall() {
        return new ToolCallRecord(
                "lookupEnergyConsumption", "meterId=DEV-ENERGY-001", "currentKwh=120");
    }

    private static VoiceAnswerAgent successfulAgent(VoiceAnswer answer) {
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        stubAgent(agent, invocation -> {
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolStarted("lookupEnergyConsumption", "meterId=DEV-ENERGY-001");
                    listener.onToolCompleted("lookupEnergyConsumption", true);
                    listener.onTextDelta(answer.text());
                    return answer;
                });
        return agent;
    }

    private static VoiceAnswerAgent blockingSuccessfulAgent(
            CountDownLatch started, CountDownLatch release) {
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        stubAgent(agent, invocation -> {
                    started.countDown();
                    try {
                        release.await();
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolCompleted("lookupEnergyConsumption", true);
                    return validAnswer();
                });
        return agent;
    }

    private static void stubAgent(VoiceAnswerAgent agent, Answer<VoiceAnswer> answer) {
        when(agent.answer(anyString(), anyString(), eq(QUESTION),
                any(VoiceAnswerAgent.Listener.class))).thenAnswer(answer);
        when(agent.answer(anyString(), anyString(), eq(QUESTION),
                any(VoiceAnswerAgent.Listener.class), any(BooleanSupplier.class)))
                .thenAnswer(answer);
    }

    private static void awaitWaiting(Thread thread) {
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private enum AsrBehavior {
        CLOSED,
        ERROR_THEN_CLOSED,
        BLOCK,
        START_THROWS,
        SEND_THROWS,
        COMMIT_THROWS,
        MISMATCH_ERROR_THEN_CLOSED,
        CONFLICT_CLOSE_THEN_ERROR
    }

    private static class ScriptedAsrPort implements StreamingAsrPort {
        private final AsrBehavior behavior;
        protected final List<byte[]> frames = new ArrayList<>();
        protected final CountDownLatch commitLatch = new CountDownLatch(1);
        protected String sessionId;
        protected String turnId;
        protected Listener listener;
        protected boolean started;
        protected boolean committed;
        protected int cancelAttempts;
        protected boolean throwOnCancel;

        private ScriptedAsrPort(AsrBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public void start(String sessionId, String turnId, Listener listener) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.listener = listener;
            started = true;
            if (behavior == AsrBehavior.START_THROWS) {
                throw new IllegalStateException("ASR provider start failed after reservation");
            }
        }

        @Override
        public void send(String sessionId, String turnId, byte[] pcmChunk) {
            frames.add(pcmChunk);
            if (behavior == AsrBehavior.SEND_THROWS && frames.size() == 3) {
                throw new IllegalStateException("ASR send failed");
            }
        }

        @Override
        public void commit(String sessionId, String turnId) {
            committed = true;
            commitLatch.countDown();
            switch (behavior) {
                case CLOSED -> listener.onClosed(sessionId, turnId);
                case ERROR_THEN_CLOSED -> {
                    listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                    listener.onClosed(sessionId, turnId);
                }
                case BLOCK -> { }
                case COMMIT_THROWS -> throw new IllegalStateException("ASR commit failed");
                case MISMATCH_ERROR_THEN_CLOSED -> {
                    listener.onError(sessionId + "-mismatch", turnId,
                            VoiceErrorCode.PROVIDER_FAILURE);
                    listener.onClosed(sessionId, turnId);
                }
                case CONFLICT_CLOSE_THEN_ERROR -> {
                    listener.onClosed(sessionId, turnId);
                    listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                }
                case START_THROWS, SEND_THROWS ->
                        throw new IllegalStateException("ASR reached an impossible phase");
            }
        }

        @Override
        public void cancel(String sessionId, String turnId) {
            cancelAttempts++;
            if (throwOnCancel) {
                throw new IllegalStateException("ASR cancel failed");
            }
        }
    }

    private static final class ExternallyClosedAsrPort extends ScriptedAsrPort {
        private final CountDownLatch mismatchedCloseSent = new CountDownLatch(1);

        private ExternallyClosedAsrPort() {
            super(AsrBehavior.BLOCK);
        }

        @Override
        public void commit(String sessionId, String turnId) {
            committed = true;
            listener.onClosed(sessionId + "-mismatch", turnId);
            mismatchedCloseSent.countDown();
        }

        private void emitMatchingClose() {
            if (listener != null) {
                listener.onClosed(sessionId, turnId);
            }
        }
    }

    private enum TtsBehavior {
        COMPLETED,
        ERROR,
        INTERRUPTED,
        BLOCK,
        START_THROWS,
        MISMATCH_AUDIO_THEN_COMPLETED,
        MISMATCH_CALLBACKS_THEN_MATCHING_SUCCESS,
        CONFLICT_COMPLETED_THEN_ERROR,
        CONFLICT_ERROR_THEN_COMPLETED
    }

    private static final class ScriptedTtsPort implements StreamingTtsPort {
        private final TtsBehavior behavior;
        private final List<byte[]> audioChunks;
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private String sessionId;
        private String turnId;
        private List<String> textSegments = List.of();
        private boolean started;
        private int cancelAttempts;
        private boolean throwOnCancel;

        private ScriptedTtsPort(TtsBehavior behavior, byte[]... audioChunks) {
            this.behavior = behavior;
            this.audioChunks = List.of(audioChunks);
        }

        @Override
        public void start(String sessionId, String turnId, List<String> textSegments,
                          Listener listener) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.textSegments = List.copyOf(textSegments);
            started = true;
            startedLatch.countDown();
            if (behavior == TtsBehavior.START_THROWS) {
                throw new IllegalStateException("TTS provider start failed after reservation");
            }
            switch (behavior) {
                case COMPLETED -> {
                    emitMatchingAudio(listener);
                    listener.onCompleted(sessionId, turnId);
                }
                case ERROR -> {
                    emitMatchingAudio(listener);
                    listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                }
                case INTERRUPTED -> {
                    emitMatchingAudio(listener);
                    listener.onInterrupted(sessionId, turnId);
                }
                case BLOCK -> { }
                case MISMATCH_AUDIO_THEN_COMPLETED -> {
                    listener.onAudioChunk(sessionId + "-mismatch", turnId, 1,
                            audioChunks.get(0));
                    listener.onCompleted(sessionId, turnId);
                }
                case MISMATCH_CALLBACKS_THEN_MATCHING_SUCCESS -> {
                    listener.onAudioChunk(sessionId + "-mismatch", turnId, 1,
                            audioChunks.get(0));
                    listener.onError(sessionId, turnId + "-mismatch",
                            VoiceErrorCode.PROVIDER_FAILURE);
                    listener.onAudioChunk(sessionId, turnId, 1, audioChunks.get(1));
                    listener.onCompleted(sessionId, turnId);
                }
                case CONFLICT_COMPLETED_THEN_ERROR -> {
                    emitMatchingAudio(listener);
                    listener.onCompleted(sessionId, turnId);
                    listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                }
                case CONFLICT_ERROR_THEN_COMPLETED -> {
                    emitMatchingAudio(listener);
                    listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                    listener.onCompleted(sessionId, turnId);
                }
                case START_THROWS -> throw new IllegalStateException("unreachable");
            }
        }

        private void emitMatchingAudio(Listener listener) {
            for (int index = 0; index < audioChunks.size(); index++) {
                listener.onAudioChunk(sessionId, turnId, index + 1, audioChunks.get(index));
            }
        }

        @Override
        public void cancel(String sessionId, String turnId) {
            cancelAttempts++;
            if (throwOnCancel) {
                throw new IllegalStateException("TTS cancel failed");
            }
        }
    }

    private enum CancelFailure {
        ASR,
        TTS
    }

    private static final class ProbeThread {
        private final AtomicReference<ShowcaseProbeResult> result = new AtomicReference<>();
        private final AtomicBoolean interruptedAfterProbe = new AtomicBoolean();
        private final Thread thread;

        private ProbeThread(VoiceAssistantPreflightProbe probe) {
            thread = new Thread(() -> {
                result.set(probe.probe());
                interruptedAfterProbe.set(Thread.currentThread().isInterrupted());
            });
        }

        private void start() {
            thread.start();
        }

        private void join() throws InterruptedException {
            thread.join();
        }

        private void interruptAndJoin() throws InterruptedException {
            thread.interrupt();
            thread.join();
        }
    }
}
