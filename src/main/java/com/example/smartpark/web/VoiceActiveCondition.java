package com.example.smartpark.web;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.lang.NonNull;

/**
 * True when voice is explicitly enabled AND real DashScope credentials exist.
 * Used to register scanned components (e.g. the REST controller) only when the
 * whole voice layer can actually function; enabling without credentials fails
 * startup in {@code VoiceSessionConfiguration} instead of hiding the feature.
 */
public class VoiceActiveCondition implements Condition {

    private static final String ENABLED_KEY = "smartpark.voice.enabled";
    private static final String PROPERTY_KEY = "spring.ai.dashscope.api-key";
    private static final String ENV_KEY = "AI_DASHSCOPE_API_KEY";

    @Override
    public boolean matches(@NonNull ConditionContext context, @NonNull AnnotatedTypeMetadata metadata) {
        if (!Boolean.parseBoolean(context.getEnvironment().getProperty(ENABLED_KEY))) {
            return false;
        }
        String envValue = context.getEnvironment().getProperty(ENV_KEY);
        String propertyValue = context.getEnvironment().getProperty(PROPERTY_KEY);
        return hasText(envValue) || hasText(propertyValue);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
