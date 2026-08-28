package com.example.smartpark.voice.model;

/** ANSWER_DELTA broadcast: one streamed answer fragment. */
public record AnswerDeltaFrame(
        String sessionId, String messageId, long sequence,
        String delta)
        implements VoiceServerFrame {

    public AnswerDeltaFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (delta == null || delta.isEmpty()) {
            throw new IllegalArgumentException("delta must not be empty");
        }
    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.ANSWER_DELTA;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
