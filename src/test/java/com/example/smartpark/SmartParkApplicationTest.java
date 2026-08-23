package com.example.smartpark;

import com.example.smartpark.adapter.mock.MockParkConfiguration;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties = {
                "spring.ai.dashscope.enabled=false",
                "spring.ai.dashscope.chat.options.model=qwen-plus"
        })
class SmartParkApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationContextLoads() {
        assertThat(applicationContext.getBeansOfType(AlertTriageAgent.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AlertDiagnosisAgent.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MockParkConfiguration.class)).isEmpty();
    }

    @Test
    void mapsDashScopeApiKeyFromEnvironment() {
        assertEquals(
                System.getenv().getOrDefault("AI_DASHSCOPE_API_KEY", ""),
                environment.getProperty("spring.ai.dashscope.api-key", ""));
    }
}
