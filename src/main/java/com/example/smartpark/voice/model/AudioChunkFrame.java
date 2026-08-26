package com.example.smartpark.voice.model;

/** AUDIO_CHUNK announcement; raw PCM travels as the next binary WS message, never inside JSON. */
public record AudioChunkFrame(
        String sessionId, String messageId, long sequence,
        int chunkSequence, int sizeBytes)
        implements VoiceServerFrame {

    public AudioChunkFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (chunkSequence <= 0) {
            throw new IllegalArgumentException("chunkSequence starts at 1");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.AUDIO_CHUNK;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
