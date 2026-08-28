package com.example.smartpark.voice.model;

/**
 * Shared identity contract carried by every JSON frame in both directions.
 * Frames without sessionId/messageId/sequence are invalid by construction.
 */
public record VoiceEnvelope(String sessionId, String messageId, long sequence) {

    public VoiceEnvelope {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }
}
