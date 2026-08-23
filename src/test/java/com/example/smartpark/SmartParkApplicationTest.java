package com.example.smartpark;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        properties = {
                "spring.ai.dashscope.enabled=false",
                "spring.ai.dashscope.chat.options.model=qwen-plus"
        })
class SmartParkApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void applicationContextLoads() {
    }

    @Test
    void mapsDashScopeApiKeyFromEnvironment() {
        assertEquals(
                System.getenv().getOrDefault("AI_DASHSCOPE_API_KEY", ""),
                environment.getProperty("spring.ai.dashscope.api-key", ""));
    }
}
