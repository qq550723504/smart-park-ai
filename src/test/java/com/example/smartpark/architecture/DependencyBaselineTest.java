package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gates the runtime dependency baseline for the P1 roadmap: Spring Boot 4 and
 * Spring AI 2.0 must be the resolved versions before any P1 feature work starts.
 */
class DependencyBaselineTest {

    @Test
    void runsOnSpringBoot4AndSpringAi2() {
        assertThat(SpringBootVersion.getVersion()).startsWith("4.0.");
        assertThat(org.springframework.ai.chat.model.ChatModel.class.getPackage()
                .getImplementationVersion()).startsWith("2.0.");
        assertThat(com.alibaba.cloud.ai.graph.StateGraph.class.getPackage()
                .getImplementationVersion()).isEqualTo("2.0.0-M1.1");
    }
}
