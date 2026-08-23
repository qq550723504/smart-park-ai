package com.example.smartpark.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in real-provider verification. It never runs during the normal offline test suite.
 */
@Tag("dashscope")
@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=true",
        "spring.ai.dashscope.chat.options.model=qwen-plus"
})
@EnabledIf(expression = "#{systemProperties['run.dashscope.smoke'] == 'true'}", loadContext = false)
@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
class DashScopeSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void callsQwenPlusWhenExplicitlyEnabled() {
        ChatResponse response = chatModel.call(new Prompt(
                new UserMessage("Reply with exactly DASH_SCOPE_CONNECTED.")));

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput().getText()).isNotBlank();
    }
}
