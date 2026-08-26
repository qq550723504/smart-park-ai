package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceDeadlines;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        private String maxInputDuration = "PT10S";
        /** Max agent reasoning+tools+streaming time (plan: 15s). */
        private String maxAgentDuration = "PT15S";
        /** Max wait for the first TTS chunk (plan: 5s). */
        private String ttsFirstChunkTimeout = "PT5S";

        public VoiceDeadlines toDeadlines() {
            return new VoiceDeadlines(
                    java.time.Duration.parse(maxInputDuration),
                    java.time.Duration.parse(maxAgentDuration),
                    java.time.Duration.parse(ttsFirstChunkTimeout));
        }

        public String getMaxInputDuration() {
            return maxInputDuration;
        }

        public void setMaxInputDuration(String value) {
            this.maxInputDuration = value;
        }

        public String getMaxAgentDuration() {
            return maxAgentDuration;
        }

        public void setMaxAgentDuration(String value) {
            this.maxAgentDuration = value;
        }

        public String getTtsFirstChunkTimeout() {
            return ttsFirstChunkTimeout;
        }

        public void setTtsFirstChunkTimeout(String value) {
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
