package com.example.smartpark;

import com.example.smartpark.adapter.mock.MockParkConfiguration;
import com.example.smartpark.web.CustomerServiceController;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mcpIsDisabledByDefault() throws Exception {
        assertThat(applicationContext.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        mockMvc.perform(post("/mcp").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void applicationContextLoads() {
        assertThat(applicationContext.getBeansOfType(AlertTriageAgent.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AlertDiagnosisAgent.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(MockParkConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(CustomerServiceWorkflow.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(CustomerServiceController.class)).hasSize(1);
    }

    @Test
    void mapsDashScopeApiKeyFromEnvironment() {
        assertEquals(
                System.getenv().getOrDefault("AI_DASHSCOPE_API_KEY", ""),
                environment.getProperty("spring.ai.dashscope.api-key", ""));
    }
}
