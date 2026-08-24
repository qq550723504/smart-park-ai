package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

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
}
