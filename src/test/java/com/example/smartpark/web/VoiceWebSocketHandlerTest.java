package com.example.smartpark.web;

import com.example.smartpark.voice.model.VoiceSessionState;
import com.example.smartpark.voice.VoiceTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.net.URI;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import org.mockito.Mockito;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceWebSocketHandlerTest {

    private final VoiceTestSupport support =
            new VoiceTestSupport("{\"intent\":\"CHITCHAT\"}", List.of());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VoiceWebSocketHandler handler = new VoiceWebSocketHandler(
            support.service, objectMapper, 64 * 1024);

    private org.springframework.web.socket.WebSocketSession wsSession(String sessionId) {
        var session = mock(org.springframework.web.socket.WebSocketSession.class);
        when(session.getUri()).thenReturn(
                URI.create("/ws/voice/sessions/" + sessionId));
        when(session.getAttributes()).thenReturn(new HashMap<>());
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

        // 超大帧：直接关闭连接。
        var oversized = mock(org.springframework.web.socket.WebSocketSession.class);
        when(oversized.getUri()).thenReturn(URI.create("/ws/voice/sessions/" + created.sessionId()));
        when(oversized.getAttributes()).thenReturn(new HashMap<>());
        handler.afterConnectionEstablished(oversized);
        byte[] tooBig = new byte[64 * 1024 + 1];
        handler.handleBinaryMessage(oversized, new BinaryMessage(ByteBuffer.wrap(tooBig)));
        verify(oversized).close(CloseStatus.NOT_ACCEPTABLE.withReason("oversized audio frame"));
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
