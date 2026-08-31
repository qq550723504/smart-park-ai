package com.example.smartpark.web;

import com.example.smartpark.voice.model.VoiceSessionState;
import com.example.smartpark.voice.VoiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import org.mockito.Mockito;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceWebSocketHandlerTest {

    private final VoiceTestSupport support =
            new VoiceTestSupport("{\"intent\":\"CHITCHAT\"}", List.of());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VoiceWebSocketHandler handler = new VoiceWebSocketHandler(
            support.service, objectMapper, 64 * 1024);

    private org.springframework.web.socket.WebSocketSession wsSession(String sessionId)
            throws java.io.IOException {
        return wsSession(sessionId, null);
    }

    private org.springframework.web.socket.WebSocketSession wsSession(
            String sessionId, List<WebSocketMessage<?>> sentMessages) throws java.io.IOException {
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getUri()).thenReturn(
                URI.create("/ws/voice/sessions/" + sessionId));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        if (sentMessages != null) {
            doAnswer(invocation -> {
                sentMessages.add(invocation.getArgument(0));
                return null;
            }).when(session).sendMessage(any());
        }
        return session;
    }

    @Test
    void connectionToUnknownSessionIsRejected() throws Exception {
        var ws = wsSession("vs-unknown");
        handler.afterConnectionEstablished(ws);
        verify(ws).close(CloseStatus.NOT_ACCEPTABLE.withReason("unknown session"));
    }

    @Test
    void controlFrameDrivesStateMachineAndInvalidFramesGetSafeErrors() throws Exception {
        var created = support.service.createSession(null);
        var ws = wsSession(created.sessionId());
        handler.afterConnectionEstablished(ws);

        handler.handleTextMessage(ws, new TextMessage(objectMapper.writeValueAsString(
                Map.of("type", "start_input", "messageId", "m1", "sequence", 1))));

        var session = support.store.find(created.sessionId()).orElseThrow();
        session.flush();
        assertThat(session.state()).isEqualTo(VoiceSessionState.LISTENING);

        // 非法 JSON：回发 INVALID_FRAME 错误帧而不是关闭连接。
        Mockito.clearInvocations(ws);
        handler.handleTextMessage(ws, new TextMessage("{not json"));
        verify(ws).sendMessage(any(TextMessage.class));

        // 非法 control type 同样安全拒绝。
        Mockito.clearInvocations(ws);
        handler.handleTextMessage(ws, new TextMessage(
                objectMapper.writeValueAsString(Map.of("type", "explode", "sequence", 2))));
        verify(ws).sendMessage(any(TextMessage.class));
    }

    @Test
    void binaryAudioWithinLimitIsForwardedAndOversizedClosesTheConnection() throws Exception {
        var created = support.service.createSession(null);
        var ws = wsSession(created.sessionId());
        handler.afterConnectionEstablished(ws);

        handler.handleTextMessage(ws, new TextMessage(objectMapper.writeValueAsString(
                Map.of("type", "start_input", "messageId", "m1", "sequence", 1))));
        handler.handleBinaryMessage(ws, smallAudio());

        var session = support.store.find(created.sessionId()).orElseThrow();
        session.flush();
        assertThat(session.state()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(session.ringBuffer().sizeBytes()).isGreaterThan(0);

        // 超大帧：直接关闭连接。（新会话，避免触发“已被占用”的 attach 保护）
        var oversized = support.service.createSession(null);
        var oversizedWs = wsSession(oversized.sessionId());
        handler.afterConnectionEstablished(oversizedWs);
        byte[] tooBig = new byte[64 * 1024 + 1];
        handler.handleBinaryMessage(oversizedWs, new BinaryMessage(ByteBuffer.wrap(tooBig)));
        verify(oversizedWs).close(CloseStatus.NOT_ACCEPTABLE.withReason("oversized audio frame"));
    }

    @Test
    void secondAttachToSameSessionIsRejected() throws Exception {
        var created = support.service.createSession(null);
        var first = wsSession(created.sessionId());
        var second = wsSession(created.sessionId());

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);

        // 第二条连接不能劫持正在进行的音频流。
        verify(second).close(CloseStatus.NOT_ACCEPTABLE.withReason("session already attached"));
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        assertThat(support.store.find(created.sessionId())).isPresent();
    }

    @Test
    void oneTurnFlowsFromWebSocketInputThroughProvidersBackToWebSocketOutput() throws Exception {
        var voiceSupport = new VoiceTestSupport(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 楼当前用电 138 千瓦时，高于基线 100 千瓦时。"));
        var voiceHandler = new VoiceWebSocketHandler(
                voiceSupport.service, objectMapper, 64 * 1024);
        var created = voiceSupport.service.createSession(null);
        var voiceSession = voiceSupport.store.find(created.sessionId()).orElseThrow();
        var sent = new CopyOnWriteArrayList<WebSocketMessage<?>>();
        var ws = wsSession(created.sessionId(), sent);
        voiceHandler.afterConnectionEstablished(ws);

        voiceHandler.handleTextMessage(ws, new TextMessage(objectMapper.writeValueAsString(
                Map.of("type", "start_input", "messageId", "m1", "sequence", 1))));
        voiceSession.flush();
        String turnId = voiceSupport.asrPort.startedTurns().get(0)
                .substring(created.sessionId().length() + 1);

        voiceHandler.handleBinaryMessage(ws, smallAudio());
        voiceHandler.handleTextMessage(ws, new TextMessage(objectMapper.writeValueAsString(
                Map.of("type", "commit_input", "messageId", "m2", "sequence", 2))));
        voiceSession.flush();

        voiceSupport.asrPort.emitPartial(created.sessionId(), turnId, "A2 楼现在用了多少电");
        voiceSupport.asrPort.emitFinal(created.sessionId(), turnId, "A2 楼现在用了多少电？");
        voiceSession.flush();

        assertThat(voiceSupport.ttsPort.requestedTexts(created.sessionId())).isNotEmpty();
        voiceSupport.ttsPort.emitChunk(created.sessionId(), turnId, new byte[] {1, 2, 3, 4});
        voiceSupport.ttsPort.completeTurn(created.sessionId(), turnId);
        voiceSession.flush();

        List<String> frameTypes = sent.stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(message -> readType(message.getPayload()))
                .toList();
        assertThat(frameTypes).contains("SESSION_STATE", "ASR_PARTIAL", "ASR_FINAL",
                "TOOL_EVENT", "ANSWER_DELTA", "AUDIO_CHUNK");
        assertThat(sent.stream().filter(BinaryMessage.class::isInstance)).hasSize(1);
        assertThat(voiceSession.state()).isEqualTo(VoiceSessionState.IDLE);
        assertThat(voiceSupport.asrPort.sentChunks(created.sessionId())).hasSize(1);
        assertThat(voiceSupport.asrPort.committedTurns()).containsExactly(turnId);
    }

    private String readType(String json) {
        try {
            return objectMapper.readTree(json).path("type").asText();
        } catch (Exception ex) {
            throw new AssertionError("invalid outbound voice frame", ex);
        }
    }

    private BinaryMessage smallAudio() {
        return new BinaryMessage(ByteBuffer.wrap(new byte[640]));
    }

    @Test
    void disconnectTriggersFullCleanup() throws Exception {
        var created = support.service.createSession(null);
        var ws = wsSession(created.sessionId());
        handler.afterConnectionEstablished(ws);

        handler.handleTextMessage(ws, new TextMessage(
                objectMapper.writeValueAsString(Map.of("type", "close_session",
                        "messageId", "m9", "sequence", 9))));
        Thread.sleep(50); // close runs on the serial executor
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        assertThat(support.store.find(created.sessionId())).isEmpty();
        // 第二次断开是安全的 no-op。
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
    }
}
