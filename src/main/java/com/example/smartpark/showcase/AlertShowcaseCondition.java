package com.example.smartpark.showcase;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class AlertShowcaseCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean modelEnabled = Boolean.parseBoolean(
                environment.getProperty("spring.ai.dashscope.enabled", "true"));
        return modelEnabled
                && "rag".equals(environment.getProperty("smartpark.knowledge.mode", "mock"))
                && "dashscope".equals(environment.getProperty(
                        "smartpark.customer-service.answer-mode", "mock"));
    }
}
