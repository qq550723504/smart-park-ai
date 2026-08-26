package com.example.smartpark.voice.model;

/** Client control frame: JSON text message carrying a session control command. */
public record ClientControlFrame(
        VoiceClientControlType type, String sessionId, String messageId, long sequence)
        implements VoiceFrame {

    public ClientControlFrame {
        new VoiceEnvelope(sessionId, messageId, sequence); // identity validation
        if (type == null) {
            throw new IllegalArgumentException("control type must not be null");
        }
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
