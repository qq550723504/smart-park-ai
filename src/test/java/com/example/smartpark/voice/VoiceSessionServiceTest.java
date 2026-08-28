package com.example.smartpark.voice;

import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.voice.model.VoiceClientControlType;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.model.VoiceSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceSessionServiceTest {

    private VoiceTestSupport harness(String classifyJson, List<String> chunks) {
        return new VoiceTestSupport(classifyJson, chunks);
    }

    private void startTurn(VoiceTestSupport h, VoiceTestSupport.Created session) {
        service(h, session).handleControl(session.info().sessionId(), control(
                session, VoiceClientControlType.START_INPUT));
        h.flush(session.session());
    }

    private void commitTurn(VoiceTestSupport h, VoiceTestSupport.Created session) {
        service(h, session).handleControl(session.info().sessionId(), control(
                session, VoiceClientControlType.COMMIT_INPUT));
        h.flush(session.session());
    }

    private com.example.smartpark.voice.model.ClientControlFrame control(
            VoiceTestSupport.Created session, VoiceClientControlType type) {
        long seq = session.session().nextFrameSequence();
        return new com.example.smartpark.voice.model.ClientControlFrame(
                type, session.info().sessionId(), "ctl-" + seq, seq);
    }

    private VoiceSessionService service(VoiceTestSupport h, VoiceTestSupport.Created session) {
        // single-service harness; kept as a method for readability in tests
        return h.service;
    }

    @Test
    void fullHappyPathAlignsAsrToolsAnswerAndTtsChunks() {
        VoiceTestSupport h = harness(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时，高于基线 100 千瓦时。"));
        var session = h.createSession();

        startTurn(h, session);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.LISTENING);

        h.service.handleBinaryAudio(session.info().sessionId(), new byte[640]);
        h.flush(session.session());

        commitTurn(h, session);

        h.asrPort.emitPartial(session.info().sessionId(), session.session().currentTurnId(), "现在用");
        h.asrPort.emitFinal(session.info().sessionId(), session.session().currentTurnId(), "现在用了多少电");
        h.flush(session.session());

        h.ttsPort.emitChunk(session.info().sessionId(), session.session().currentTurnId(), new byte[]{1, 2});
        h.ttsPort.completeTurn(session.info().sessionId(), session.session().currentTurnId());
        h.flush(session.session());

        // 转写、工具、回答 delta、TTS chunk 在同一轮内对齐。
        assertThat(h.frames.partials()).hasSize(1);
        assertThat(h.frames.toolEvents()).extracting(ToolNameExtractor::name)
                .containsExactly("lookupEnergyConsumption", "lookupEnergyConsumption");
        assertThat(String.join("", h.frames.deltas())).contains("138");
        assertThat(h.frames.chunkAnnouncements()).singleElement()
                .satisfies(chunk -> assertThat(chunk.chunkSequence()).isEqualTo(1));
        assertThat(h.frames.binaries).singleElement()
                .satisfies(binary -> assertThat(binary.pcm()).containsExactly(1, 2));

        // 状态机走完整链路并回到 IDLE。
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.IDLE);
        assertThat(h.frames.states())
                .extracting(com.example.smartpark.voice.model.SessionStateFrame::state)
                .contains(VoiceSessionState.LISTENING,
                        VoiceSessionState.ASR_FINALIZED,
                        VoiceSessionState.REASONING,
                        VoiceSessionState.TOOL_CALLING,
                        VoiceSessionState.ANSWER_STREAMING,
                        VoiceSessionState.SPEAKING,
                        VoiceSessionState.IDLE);

        // 统一事件流同步发布。
        assertThat(h.events.published).isNotEmpty();
        assertThat(h.events.sawTerminalType(ExecutionEventType.AUDIO_COMPLETED)).isTrue();

        // 音频缓冲在回合结束后清零。
        assertThat(session.session().ringBuffer().sizeBytes()).isZero();
    }

    @Test
    void emptyFinalTranscriptFailsTheTurnExplicitlyButStaysRetryable() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of());
        var session = h.createSession();
        startTurn(h, session);

        commitTurn(h, session);
        h.asrPort.emitFinal(session.info().sessionId(),
                session.session().currentTurnId(), "   ");
        h.flush(session.session());

        assertThat(h.frames.lastErrorCode()).isEqualTo(VoiceErrorCode.AUDIO_REJECTED);
        assertThat(h.frames.lastErrorMessage()).contains("未识别到语音内容");
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.ERROR);
        // ERROR 可重试：新的 START_INPUT 回到 LISTENING。
        startTurn(h, session);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.LISTENING);
    }

    @Test
    void inputDeadlineExpiryCancelsListening() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of());
        var session = h.createSession();
        startTurn(h, session);

        h.deadlines.triggerAll();
        h.flush(session.session());

        assertThat(h.frames.lastErrorCode()).isEqualTo(VoiceErrorCode.TIMEOUT);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.ERROR);
        assertThat(h.asrPort.cancelledTurns()).contains(session.session().currentTurnId());
    }

    @Test
    void agentDeadlineExpiryFailsTurnBeforeAnyTtsStarts() {
        VoiceTestSupport h = harness(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时。"));
        var session = h.createSession();
        startTurn(h, session);
        commitTurn(h, session);

        h.deadlines.triggerAll(); // agent 预算（15 秒）到期
        h.flush(session.session());

        assertThat(h.frames.lastErrorCode()).isEqualTo(VoiceErrorCode.TIMEOUT);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.ERROR);
        assertThat(h.frames.sawState(VoiceSessionState.SPEAKING)).isFalse();
        assertThat(h.frames.chunkAnnouncements()).isEmpty();
    }

    @Test
    void ttsFirstChunkDeadlineFailsSpeakingTurn() {
        VoiceTestSupport h = harness(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时。"));
        var session = h.createSession();
        startTurn(h, session);
        commitTurn(h, session);
        h.asrPort.emitFinal(session.info().sessionId(),
                session.session().currentTurnId(), "现在用了多少电");
        h.flush(session.session());

        assertThat(session.session().state()).isEqualTo(VoiceSessionState.SPEAKING);
        int deadlinesBefore = h.deadlines.pending.size();
        assertThat(deadlinesBefore).isGreaterThan(0);

        h.deadlines.triggerAll(); // TTS 首块预算（5 秒）到期
        h.flush(session.session());

        assertThat(h.frames.lastErrorCode()).isEqualTo(VoiceErrorCode.TIMEOUT);
        assertThat(h.ttsPort.cancelledTurns()).contains(session.session().currentTurnId());
    }

    @Test
    void micClickDuringSpeakingInterruptsOutputThenListensAgain() {
        VoiceTestSupport h = harness(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时。"));
        var session = h.createSession();
        startTurn(h, session);
        commitTurn(h, session);
        h.asrPort.emitFinal(session.info().sessionId(),
                session.session().currentTurnId(), "现在用了多少电");
        h.flush(session.session());
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.SPEAKING);
        String interruptedTurnId = session.session().currentTurnId();

        // 第二次点击麦克风：先中断当前 TTS，再进入新一轮监听。
        startTurn(h, session);

        assertThat(h.ttsPort.cancelledTurns()).contains(interruptedTurnId);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(session.session().currentTurnId()).isNotEqualTo(interruptedTurnId);
        // 晚到的 TTS 块属于旧 turn，不再发布。
        int announcementsBefore = h.frames.chunkAnnouncements().size();
        h.ttsPort.emitChunk(session.info().sessionId(), interruptedTurnId, new byte[]{9});
        h.flush(session.session());
        assertThat(h.frames.chunkAnnouncements()).hasSize(announcementsBefore);
    }

    @Test
    void duplicateCommitIsRejectedWithoutBreakingTheSession() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of());
        var session = h.createSession();
        startTurn(h, session);
        commitTurn(h, session);
        int errorCount = countErrors(h);

        commitTurn(h, session); // 重复 COMMIT_INPUT

        assertThat(countErrors(h)).isEqualTo(errorCount + 1);
        assertThat(h.frames.lastError().code()).isEqualTo(VoiceErrorCode.UNSUPPORTED_STATE);
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.ASR_FINALIZED);
    }

    @Test
    void providerStartFailureBecomesAnExplicitRetryableError() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of());
        var session = h.createSession();
        h.asrPort.failOnStart(new IllegalStateException("provider unavailable"));

        startTurn(h, session);

        assertThat(session.session().state()).isEqualTo(VoiceSessionState.ERROR);
        assertThat(h.frames.lastErrorCode()).isEqualTo(VoiceErrorCode.PROVIDER_FAILURE);
    }

    @Test
    void providerFinalBeforeCommitIsPublishedButDoesNotStartReasoning() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of("你好"));
        var session = h.createSession();
        startTurn(h, session);
        String turnId = session.session().currentTurnId();

        h.asrPort.emitFinalBeforeCommit(session.info().sessionId(), turnId, "你好");
        h.flush(session.session());

        assertThat(session.session().state()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(h.frames.sawState(VoiceSessionState.REASONING)).isFalse();

        commitTurn(h, session);
        h.flush(session.session());
        assertThat(session.session().state()).isEqualTo(VoiceSessionState.SPEAKING);
    }

    @Test
    void closingSessionRemovesItsMessageCounter() {
        VoiceTestSupport h = harness("{\"intent\":\"CHITCHAT\"}", List.of());
        var session = h.createSession();
        startTurn(h, session);
        assertThat(h.service.messageCounterCount()).isGreaterThan(0);

        h.service.closeSession(session.info().sessionId());
        h.flush(session.session());

        assertThat(h.service.messageCounterCount()).isZero();
    }

    @Test
    void closeReleasesAudioAndTwoSessionsStayIsolated() {
        VoiceTestSupport h = harness(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时。"));
        var first = h.createSession();
        var second = h.createSession();

        startTurn(h, first);
        h.service.handleBinaryAudio(first.info().sessionId(), new byte[3200]);
        h.flush(first.session());
        assertThat(first.session().ringBuffer().sizeBytes()).isGreaterThan(0);

        // 第二个会话完全独立：无音频输入时不受影响。
        startTurn(h, second);
        h.service.closeSession(second.info().sessionId());
        h.flush(second.session());

        assertThat(second.session().state()).isEqualTo(VoiceSessionState.CLOSED);
        assertThat(first.session().state()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(h.store.find(second.info().sessionId())).isEmpty();

        // CLOSE 关闭第一个会话：ASR/TTS 取消、buffer 清零、事件流终止。
        h.service.closeSession(first.info().sessionId());
        h.flush(first.session());
        assertThat(first.session().state()).isEqualTo(VoiceSessionState.CLOSED);
        assertThat(first.session().ringBuffer().sizeBytes()).isZero();
        assertThat(h.asrPort.cancelledTurns()).contains(first.session().currentTurnId());
        assertThat(h.events.sawTerminalType(ExecutionEventType.COMPLETED)).isTrue();
        assertThat(h.store.find(first.info().sessionId())).isEmpty();
    }

    private static int countErrors(VoiceTestSupport h) {
        return (int) h.frames.frames.stream()
                .filter(com.example.smartpark.voice.model.ErrorFrame.class::isInstance).count();
    }

    /** Extraction helper keeping the tool-event assertion above readable. */
    private static final class ToolNameExtractor {
        static String name(com.example.smartpark.voice.model.ToolEventFrame frame) {
            return frame.toolName();
        }
    }
}
