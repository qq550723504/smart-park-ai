package com.example.smartpark.voice.model;

/**
 * SESSION_STATE broadcast. Carries only display-safe state; no raw audio,
 * no prompt text, no provider internals.
 */
public record SessionStateFrame(
        String sessionId, String messageId, long sequence,
        VoiceSessionState state, String turnId)
        implements VoiceServerFrame {

    public SessionStateFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        turnId = (turnId == null || turnId.isBlank()) ? null : turnId;
    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.SESSION_STATE;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
