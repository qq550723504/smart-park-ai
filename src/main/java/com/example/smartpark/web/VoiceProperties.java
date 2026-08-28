package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceDeadlines;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Voice session transport policy and budgets; secrets stay in environment variables. */
@ConfigurationProperties(prefix = "smartpark.voice")
public class VoiceProperties {

    /** Opt-in switch; enabling voice without provider credentials fails startup. */
    private boolean enabled = false;

    /** CORS origins allowed to open the voice WebSocket handshake.
     *  Empty by default = fail-closed: Spring then permits same-origin only,
     *  so deployments must opt in explicitly to cross-origin microphone access. */
    private java.util.List<String> allowedOrigins = java.util.List.of();

    /** Hard cap for one inbound binary audio frame at the WS edge. */
    private int maxBinaryFrameBytes = 64 * 1024;

    private Budgets budgets = new Budgets();

    public static class Budgets {
        /** Max cumulative input per turn (plan: 10s). */
        private Duration maxInputDuration = Duration.ofSeconds(10);
        /** Max agent reasoning+tools+streaming time (plan: 15s). */
        private Duration maxAgentDuration = Duration.ofSeconds(15);
        /** Max wait for the first TTS chunk (plan: 5s). */
        private Duration ttsFirstChunkTimeout = Duration.ofSeconds(5);

        public VoiceDeadlines toDeadlines() {
            return new VoiceDeadlines(
                    maxInputDuration, maxAgentDuration, ttsFirstChunkTimeout);
        }

        public Duration getMaxInputDuration() {
            return maxInputDuration;
        }

        public void setMaxInputDuration(Duration value) {
            this.maxInputDuration = value;
        }

        public Duration getMaxAgentDuration() {
            return maxAgentDuration;
        }

        public void setMaxAgentDuration(Duration value) {
            this.maxAgentDuration = value;
        }

        public Duration getTtsFirstChunkTimeout() {
            return ttsFirstChunkTimeout;
        }

        public void setTtsFirstChunkTimeout(Duration value) {
            this.ttsFirstChunkTimeout = value;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public java.util.List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(java.util.List<String> allowedOrigins) {
        this.allowedOrigins =
                allowedOrigins == null ? java.util.List.of() : java.util.List.copyOf(allowedOrigins);
    }

    public int getMaxBinaryFrameBytes() {
        return maxBinaryFrameBytes;
    }

    public void setMaxBinaryFrameBytes(int maxBinaryFrameBytes) {
        this.maxBinaryFrameBytes = maxBinaryFrameBytes;
    }

    public Budgets getBudgets() {
        return budgets;
    }

    public void setBudgets(Budgets budgets) {
        this.budgets = budgets;
    }
}
