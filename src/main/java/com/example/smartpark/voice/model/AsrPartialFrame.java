package com.example.smartpark.voice.model;

/** ASR_PARTIAL broadcast: interim transcript text. */
public record AsrPartialFrame(
        String sessionId, String messageId, long sequence,
        String text)
        implements VoiceServerFrame {

    public AsrPartialFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.ASR_PARTIAL;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
