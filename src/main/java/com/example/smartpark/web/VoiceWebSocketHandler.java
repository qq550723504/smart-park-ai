package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceFramePublisher;
import com.example.smartpark.voice.VoiceSessionService;
import com.example.smartpark.voice.model.ClientControlFrame;
import com.example.smartpark.voice.model.ErrorFrame;
import com.example.smartpark.voice.model.VoiceClientControlType;
import com.example.smartpark.voice.model.VoiceErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridges one WS connection to the voice session service: JSON text frames are
 * control commands, binary frames are PCM audio. Unknown sessions and oversized
 * binary frames are rejected at this edge; disconnects trigger full cleanup.
 */
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);
    private static final String SESSION_ATTR = "voice.sessionId";

    private final VoiceSessionService service;
    private final ObjectMapper objectMapper;
    private final int maxBinaryFrameBytes;

    public VoiceWebSocketHandler(VoiceSessionService service,
                                 ObjectMapper objectMapper,
                                 int maxBinaryFrameBytes) {
        this.service = Objects.requireNonNull(service);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.maxBinaryFrameBytes = maxBinaryFrameBytes;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession wsSession) {
        String sessionId = sessionIdOf(wsSession);
        if (sessionId == null || service.stateOf(sessionId).isEmpty()) {
            closeQuietly(wsSession, "unknown session");
            return;
        }
        if (!service.attach(sessionId, publisherFor(wsSession))) {
            closeQuietly(wsSession, "session already attached");
            return;
        }
        wsSession.getAttributes().put(SESSION_ATTR, sessionId);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession wsSession, @NonNull TextMessage message) {
        String sessionId = (String) wsSession.getAttributes().get(SESSION_ATTR);
        if (sessionId == null) {
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            VoiceClientControlType type = VoiceClientControlType.valueOf(
                    json.path("type").asText("").toUpperCase());
            ClientControlFrame frame = new ClientControlFrame(
                    type, sessionId, json.path("messageId").asText(null), json.path("sequence").asLong(-1));
            service.handleControl(sessionId, frame);
        } catch (Exception ex) {
            log.debug("invalid control frame from {}: {}", sessionId, ex.getClass().getSimpleName());
            try {
                sendSafely(wsSession, objectMapper.writeValueAsString(new ErrorFrame(
                        sessionId, sessionId + "-edge", 0,
                        VoiceErrorCode.INVALID_FRAME, "非法控制帧")));
            } catch (JacksonException jpe) {
                log.debug("error frame serialization failed: {}", jpe.getClass().getSimpleName());
            }
        }
    }

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession wsSession, @NonNull BinaryMessage message) {
        String sessionId = (String) wsSession.getAttributes().get(SESSION_ATTR);
        if (sessionId == null) {
            return;
        }
        int length = message.getPayloadLength();
        if (length > maxBinaryFrameBytes) {
            closeQuietly(wsSession, "oversized audio frame");
            return;
        }
        byte[] pcm = new byte[length];
        message.getPayload().get(pcm);
        service.handleBinaryAudio(sessionId, pcm);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession wsSession, @NonNull org.springframework.web.socket.CloseStatus status) {
        String sessionId = (String) wsSession.getAttributes().get(SESSION_ATTR);
        if (sessionId != null) {
            service.handleDisconnect(sessionId);
        }
    }

    private VoiceFramePublisher publisherFor(WebSocketSession wsSession) {
        return new VoiceFramePublisher() {

            @Override
            public void publish(com.example.smartpark.voice.model.VoiceServerFrame frame) {
                try {
                    sendSafely(wsSession, objectMapper.writeValueAsString(frame));
                } catch (JacksonException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }

            @Override
            public void publishAudioChunk(int chunkSequence, byte[] pcm) {
                // Binary framing: [4-byte big-endian sequence][pcm]; never inside JSON.
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4 + pcm.length);
                buffer.putInt(chunkSequence).put(pcm).flip();
                sendRaw(wsSession, new BinaryMessage(buffer));
            }
        };
    }

    private void sendSafely(WebSocketSession wsSession, String json) {
        try {
            synchronized (wsSession) {
                wsSession.sendMessage(new TextMessage(json));
            }
        } catch (IOException | IllegalStateException ex) {
            log.debug("ws send failed on {}: {}", wsSession.getId(), ex.getClass().getSimpleName());
        }
    }

    private void sendRaw(WebSocketSession wsSession, BinaryMessage message) {
        try {
            synchronized (wsSession) {
                wsSession.sendMessage(message);
            }
        } catch (IOException | IllegalStateException ex) {
            log.debug("ws binary send failed on {}: {}", wsSession.getId(), ex.getClass().getSimpleName());
        }
    }

    private void closeQuietly(WebSocketSession wsSession, String reason) {
        try {
            wsSession.close(org.springframework.web.socket.CloseStatus.NOT_ACCEPTABLE.withReason(reason));
        } catch (IOException ex) {
            log.debug("ws close failed: {}", ex.getClass().getSimpleName());
        }
    }

    static String sessionIdOf(WebSocketSession wsSession) {
        URI uri = wsSession.getUri();
        if (uri == null || uri.getPath() == null) {
            return null;
        }
        String path = uri.getPath();
        String marker = "/ws/voice/sessions/";
        int index = path.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String remainder = path.substring(index + marker.length());
        return remainder.split("/")[0];
    }
}
