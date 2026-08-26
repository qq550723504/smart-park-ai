package com.example.smartpark.voice;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingAsrAdapter;
import com.example.smartpark.voice.port.StreamingAsrPort;
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

    @Bean
    @Conditional(RealVoiceCredentialCondition.class)
    public StreamingAsrPort dashScopeStreamingAsrPort(DashScopeAudioTranscriptionModel model) {
        return new DashScopeStreamingAsrAdapter(model);
    }

    /**
     * Matches only when a non-blank DashScope API key is configured and the
     * voice feature is not explicitly disabled.
     */
    static class RealVoiceCredentialCondition implements Condition {

        private static final String PROPERTY_KEY = "spring.ai.dashscope.api-key";
        private static final String ENV_KEY = "AI_DASHSCOPE_API_KEY";
        private static final String VOICE_ENABLED_KEY = "smartpark.voice.enabled";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if ("false".equals(context.getEnvironment().getProperty(VOICE_ENABLED_KEY))) {
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
