package com.example.smartpark.adapter.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** Keeps the public Smart Park MCP gate authoritative over the starter's internal gate. */
public final class SmartParkMcpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE_NAME = "smartParkMcpFeatureGate";
    static final String SMART_PARK_GATE = "smartpark.mcp.enabled";
    static final String SPRING_AI_GATE = "spring.ai.mcp.server.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = environment.getProperty(SMART_PARK_GATE, Boolean.class, false);
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(SPRING_AI_GATE, enabled)));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
