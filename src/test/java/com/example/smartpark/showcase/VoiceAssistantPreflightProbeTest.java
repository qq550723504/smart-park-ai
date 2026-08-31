package com.example.smartpark.showcase;

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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        byte[] providerAudio = new byte[] {1, 2};
        CompletingTtsPort tts = new CompletingTtsPort(providerAudio, TtsOutcome.COMPLETED);
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
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.cancelled).isTrue();
        verify(agent).answer(eq(asr.sessionId), eq(asr.turnId), eq(QUESTION), any());
    }

    @Test
    void failsAnAsrErrorEvenWhenTheTurnThenCloses() {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.ERROR_THEN_CLOSED);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        CompletingTtsPort tts = new CompletingTtsPort(new byte[] {1}, TtsOutcome.COMPLETED);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.started).isFalse();
        verify(agent, never()).answer(anyString(), anyString(), anyString(), any());
    }

    @Test
    void failsAndSkipsTtsWhenTheAgentThrows() {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        when(agent.answer(anyString(), anyString(), eq(QUESTION), any()))
                .thenThrow(new IllegalStateException("provider output must stay private"));
        CompletingTtsPort tts = new CompletingTtsPort(new byte[] {1}, TtsOutcome.COMPLETED);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.started).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void rejectsAgentAnswersMissingAnyRequiredEvidenceBoundary(VoiceAnswer answer) {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        CompletingTtsPort tts = new CompletingTtsPort(new byte[] {1}, TtsOutcome.COMPLETED);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(answer), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.started).isFalse();
    }

    @Test
    void rejectsAnAnswerWithoutASuccessfulToolCompletionCallback() {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        CompletingTtsPort tts = new CompletingTtsPort(new byte[] {1}, TtsOutcome.COMPLETED);
        VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
        when(agent.answer(anyString(), anyString(), eq(QUESTION), any()))
                .thenAnswer(invocation -> {
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolStarted("lookupEnergyConsumption", "meterId=DEV-ENERGY-001");
                    listener.onToolCompleted("lookupEnergyConsumption", false);
                    return validAnswer();
                });

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.started).isFalse();
    }

    @Test
    void rejectsTtsCompletionWithOnlyEmptyChunksAndCancelsTheTurn() {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        CompletingTtsPort tts = new CompletingTtsPort(new byte[0], TtsOutcome.COMPLETED);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.cancelled).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TtsOutcome.class, names = {"ERROR", "INTERRUPTED"})
    void rejectsEveryAbnormalTtsTerminalAndWipesDeliveredAudio(TtsOutcome outcome) {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        byte[] providerAudio = new byte[] {7, 8};
        CompletingTtsPort tts = new CompletingTtsPort(providerAudio, outcome);

        ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts).probe();

        assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(providerAudio).containsOnly((byte) 0);
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.cancelled).isTrue();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void interruptionWhileAwaitingTtsRestoresTheFlagAndCancelsEveryStartedTurn()
            throws InterruptedException {
        CompletingAsrPort asr = new CompletingAsrPort(AsrOutcome.CLOSED);
        BlockingTtsPort tts = new BlockingTtsPort();
        VoiceAssistantPreflightProbe probe = new VoiceAssistantPreflightProbe(
                asr, successfulAgent(validAnswer()), tts);
        AtomicReference<ShowcaseProbeResult> result = new AtomicReference<>();
        AtomicBoolean interruptedAfterProbe = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(probe.probe());
            interruptedAfterProbe.set(Thread.currentThread().isInterrupted());
        });

        worker.start();
        tts.startedLatch.await();
        worker.interrupt();
        worker.join();

        assertThat(result.get()).isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(interruptedAfterProbe).isTrue();
        assertThat(asr.cancelled).isTrue();
        assertThat(tts.cancelled).isTrue();
    }

    private static Stream<VoiceAnswer> invalidAnswers() {
        ToolCallRecord call = validToolCall();
        return Stream.of(
                new VoiceAnswer(" ", List.of("DEV-ENERGY-001"), List.of(call)),
                new VoiceAnswer("当前用电正常", List.of(), List.of(call)),
                new VoiceAnswer("当前用电正常", List.of("DEV-ENERGY-001"), List.of()));
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
        when(agent.answer(anyString(), anyString(), eq(QUESTION), any()))
                .thenAnswer(invocation -> {
                    VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                    listener.onToolStarted("lookupEnergyConsumption", "meterId=DEV-ENERGY-001");
                    listener.onToolCompleted("lookupEnergyConsumption", true);
                    listener.onTextDelta(answer.text());
                    return answer;
                });
        return agent;
    }

    private enum AsrOutcome {
        CLOSED,
        ERROR_THEN_CLOSED
    }

    private static final class CompletingAsrPort implements StreamingAsrPort {
        private final AsrOutcome outcome;
        private final List<byte[]> frames = new ArrayList<>();
        private String sessionId;
        private String turnId;
        private Listener listener;
        private boolean committed;
        protected boolean cancelled;

        private CompletingAsrPort(AsrOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public void start(String sessionId, String turnId, Listener listener) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.listener = listener;
        }

        @Override
        public void send(String sessionId, String turnId, byte[] pcmChunk) {
            frames.add(pcmChunk);
        }

        @Override
        public void commit(String sessionId, String turnId) {
            committed = true;
            if (outcome == AsrOutcome.ERROR_THEN_CLOSED) {
                listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
            }
            listener.onClosed(sessionId, turnId);
        }

        @Override
        public void cancel(String sessionId, String turnId) {
            cancelled = true;
        }
    }

    private enum TtsOutcome {
        COMPLETED,
        ERROR,
        INTERRUPTED
    }

    private static class CompletingTtsPort implements StreamingTtsPort {
        private final byte[] audio;
        private final TtsOutcome outcome;
        private String sessionId;
        private String turnId;
        private List<String> textSegments = List.of();
        protected boolean started;
        protected boolean cancelled;

        private CompletingTtsPort(byte[] audio, TtsOutcome outcome) {
            this.audio = audio;
            this.outcome = outcome;
        }

        @Override
        public void start(String sessionId, String turnId, List<String> textSegments,
                          Listener listener) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.textSegments = List.copyOf(textSegments);
            this.started = true;
            listener.onAudioChunk(sessionId, turnId, 1, audio);
            switch (outcome) {
                case COMPLETED -> listener.onCompleted(sessionId, turnId);
                case ERROR -> listener.onError(sessionId, turnId, VoiceErrorCode.PROVIDER_FAILURE);
                case INTERRUPTED -> listener.onInterrupted(sessionId, turnId);
            }
        }

        @Override
        public void cancel(String sessionId, String turnId) {
            cancelled = true;
        }
    }

    private static final class BlockingTtsPort extends CompletingTtsPort {
        private final CountDownLatch startedLatch = new CountDownLatch(1);

        private BlockingTtsPort() {
            super(new byte[0], TtsOutcome.COMPLETED);
        }

        @Override
        public void start(String sessionId, String turnId, List<String> textSegments,
                          Listener listener) {
            this.started = true;
            startedLatch.countDown();
        }
    }
}
