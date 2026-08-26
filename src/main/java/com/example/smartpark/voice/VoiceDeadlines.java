package com.example.smartpark.voice;

/** Turn budget from configuration: input 10s, agent 15s, TTS first chunk 5s by plan. */
public record VoiceDeadlines(java.time.Duration maxInputDuration,
                             java.time.Duration maxAgentDuration,
                             java.time.Duration ttsFirstChunkTimeout) {

    public VoiceDeadlines {
        if (maxInputDuration == null || maxInputDuration.isNegative() || maxInputDuration.isZero()
                || maxAgentDuration == null || maxAgentDuration.isNegative() || maxAgentDuration.isZero()
                || ttsFirstChunkTimeout == null || ttsFirstChunkTimeout.isNegative() || ttsFirstChunkTimeout.isZero()) {
            throw new IllegalArgumentException("all voice deadlines must be positive");
        }
    }

    public static VoiceDeadlines defaults() {
        return new VoiceDeadlines(
                java.time.Duration.ofSeconds(10),
                java.time.Duration.ofSeconds(15),
                java.time.Duration.ofSeconds(5));
    }
}
