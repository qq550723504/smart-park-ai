package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compilation-level proof that the Spring AI Alibaba 2.0 milestone exposes every
 * primitive the P1 scenarios need: graph parallel branching, react agents,
 * parallel agents and streaming audio models. No network calls are made.
 */
class SpringAiAlibaba2CapabilityTest {

    @Test
    void exposesRequiredP1Primitives() throws Exception {
        assertThat(Class.forName("com.alibaba.cloud.ai.graph.StateGraph")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.graph.agent.ReactAgent")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.dashscope.audio.transcription.StreamingTranscriptionModel")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.dashscope.audio.tts.StreamingInputTextToSpeechModel")).isNotNull();
    }

    @Test
    void stateGraphSupportsNativeDynamicParallelBranches() {
        Method parallelEdges = Arrays.stream(com.alibaba.cloud.ai.graph.StateGraph.class.getMethods())
                .filter(method -> method.getName().equals("addParallelConditionalEdges"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "StateGraph.addParallelConditionalEdges must exist for the dynamic expert fan-out"));
        assertThat(parallelEdges.getParameterCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void passesDashScopeCompletionsPathIntoAnalyticsComposeProfile() throws IOException {
        String compose = Files.readString(Path.of("compose.analytics.yaml"));

        assertThat(compose)
                .contains("SPRING_AI_DASHSCOPE_BASE_URL: ${SPRING_AI_DASHSCOPE_BASE_URL:-https://dashscope.aliyuncs.com}")
                .contains("SPRING_AI_DASHSCOPE_CHAT_COMPLETIONS_PATH: ${SPRING_AI_DASHSCOPE_CHAT_COMPLETIONS_PATH:-/api/v1/services/aigc/text-generation/generation}");
    }

    @Test
    void mapsDashScopeCompletionsPathFromTheEnvironment() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application)
                .contains("completions-path: ${SPRING_AI_DASHSCOPE_CHAT_COMPLETIONS_PATH:/api/v1/services/aigc/text-generation/generation}");
    }
}
