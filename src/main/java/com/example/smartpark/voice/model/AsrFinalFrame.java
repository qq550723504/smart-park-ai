package com.example.smartpark.voice.model;

/** ASR_FINAL broadcast: final transcript text of the turn. */
public record AsrFinalFrame(
        String sessionId, String messageId, long sequence,
        String text)
        implements VoiceServerFrame {

    public AsrFinalFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.ASR_FINAL;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
