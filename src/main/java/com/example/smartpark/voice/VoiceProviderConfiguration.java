package com.example.smartpark.voice;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionProperties;
import com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingAsrAdapter;
import com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingTtsAdapter;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Wires real voice providers. There are deliberately no mock/fallback beans:
 * when credentials are missing the port bean does not exist, and the voice
 * session layer hard-requires it — so misconfigured deployments fail startup
 * loudly instead of silently degrading to fake results.
 */
@Configuration(proxyBeanMethods = false)
public class VoiceProviderConfiguration {

    private static final String ASR_MODEL_PROPERTY =
            "spring.ai.dashscope.audio.transcription.options.model";
    private static final String TTS_MODEL_PROPERTY =
            "spring.ai.dashscope.audio.speech.options.model";
    private static final String TTS_VOICE_PROPERTY =
            "spring.ai.dashscope.audio.speech.options.voice";
    private static final String SUPPORTED_ASR_MODEL = "paraformer-realtime-v2";
    private static final String SUPPORTED_TTS_MODEL = "cosyvoice-v2";
    private static final String SUPPORTED_TTS_VOICE = "longxiaochun_v2";

    @Bean
    @Conditional(RealVoiceCredentialCondition.class)
    public StreamingAsrPort dashScopeStreamingAsrPort(
            DashScopeAudioTranscriptionModel model,
            DashScopeAudioTranscriptionProperties properties) {
        requireSupported(ASR_MODEL_PROPERTY, properties.getOptions().getModel(),
                SUPPORTED_ASR_MODEL);
        return new DashScopeStreamingAsrAdapter(model, properties.getOptions());
    }

    @Bean
    @Conditional(RealVoiceCredentialCondition.class)
    public StreamingTtsPort dashScopeStreamingTtsPort(
            DashScopeAudioSpeechModel speechModel,
            DashScopeAudioSpeechProperties properties) {
        requireSupported(TTS_MODEL_PROPERTY, properties.getOptions().getModel(),
                SUPPORTED_TTS_MODEL);
        requireSupported(TTS_VOICE_PROPERTY, properties.getOptions().getVoice(),
                SUPPORTED_TTS_VOICE);
        return new DashScopeStreamingTtsAdapter(speechModel, properties.getOptions());
    }

    private static void requireSupported(String property, String actual, String supported) {
        if (actual == null || actual.isBlank() || !supported.equals(actual)) {
            throw new IllegalArgumentException(property
                    + " must use the supported voice showcase value");
        }
    }

    /**
     * Matches only when a non-blank DashScope API key is configured and the
     * voice feature is explicitly enabled.
     */
    static class RealVoiceCredentialCondition implements Condition {

        private static final String PROPERTY_KEY = "spring.ai.dashscope.api-key";
        private static final String ENV_KEY = "AI_DASHSCOPE_API_KEY";
        private static final String VOICE_ENABLED_KEY = "smartpark.voice.enabled";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (!Boolean.TRUE.equals(context.getEnvironment()
                    .getProperty(VOICE_ENABLED_KEY, Boolean.class))) {
                return false;
            }
            String propertyValue = context.getEnvironment().getProperty(PROPERTY_KEY);
            String envValue = context.getEnvironment().getProperty(ENV_KEY);
            return hasText(envValue) || hasText(propertyValue);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
