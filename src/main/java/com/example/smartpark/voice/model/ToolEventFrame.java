package com.example.smartpark.voice.model;

/** TOOL_EVENT broadcast: read-only tool started/completed, display-safe fields only. */
public record ToolEventFrame(
        String sessionId, String messageId, long sequence,
        String toolName, String phase, String argumentSummary)
        implements VoiceServerFrame {

    public ToolEventFrame {
        new VoiceEnvelope(sessionId, messageId, sequence);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (!"STARTED".equals(phase) && !"COMPLETED".equals(phase)) {
            throw new IllegalArgumentException("phase must be STARTED or COMPLETED");
        }    }

    @Override
    public VoiceServerFrameType type() {
        return VoiceServerFrameType.TOOL_EVENT;
    }

    @Override
    public VoiceEnvelope envelope() {
        return new VoiceEnvelope(sessionId, messageId, sequence);
    }
}
