package com.example.smartpark.voice.model;

/** ERROR broadcast: safe code plus user-facing message; never carries provider detail. */
public record ErrorFrame(
        String sessionId, String messageId, long sequence,
        VoiceErrorCode code, String userMessage)
        implements VoiceServerFrame {

    public ErrorFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.ERROR;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
