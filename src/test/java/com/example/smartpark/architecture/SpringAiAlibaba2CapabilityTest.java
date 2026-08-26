package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
        assertThat(Class.forName("com.alibaba.cloud.ai.dashscope.api.DashScopeApi")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.dashscope.audio.transcription.StreamingTranscriptionModel")).isNotNull();
        assertThat(Class.forName("com.alibaba.cloud.ai.dashscope.audio.tts.StreamingInputTextToSpeechModel")).isNotNull();
    }

    @Test
    void declaresDashScopeImplementationDirectlyForBootJarPackaging() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Pattern directDependency = Pattern.compile(
                "<dependency>\\s*"
                        + "<groupId>com\\.alibaba\\.cloud\\.ai</groupId>\\s*"
                        + "<artifactId>spring-ai-alibaba-dashscope</artifactId>\\s*"
                        + "</dependency>", Pattern.DOTALL);

        assertThat(directDependency.matcher(pom).find())
                .as("DashScope implementation must be a direct dependency so Boot packaging includes it")
                .isTrue();
    }

    @Test
    void passesDashScopeBaseUrlIntoAnalyticsComposeProfile() throws IOException {
        String compose = Files.readString(Path.of("compose.analytics.yaml"));

        assertThat(compose)
                .contains("SPRING_AI_DASHSCOPE_BASE_URL: ${SPRING_AI_DASHSCOPE_BASE_URL:-https://dashscope.aliyuncs.com}");
    }

    @Test
    void keepsFlywayBoundToTheCanonicalAnalyticsProperty() throws IOException {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        int flywayStart = application.indexOf("  flyway:");
        int autoconfigureStart = application.indexOf("  autoconfigure:", flywayStart);
        String flyway = application.substring(flywayStart, autoconfigureStart);

        assertThat(flyway)
                .as("Flyway must follow the canonical smartpark.analytics.enabled property")
                .contains("enabled: ${smartpark.analytics.enabled:false}");
    }

    @Test
    void analyticsOwnsFlywayMigrationWhenGenericDataSourceAutoConfigurationIsExcluded() throws IOException {
        String configuration = Files.readString(
                Path.of("src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java"));

        assertThat(configuration)
                .contains("Flyway.configure()")
                .contains("flyway.migrate()");
    }

    @Test
    void provisionsAnalyticsRoleOnlyAfterFlywayMigration() throws IOException {
        String configuration = Files.readString(
                Path.of("src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java"));

        int runnerStart = configuration.indexOf("analyticsRolePasswordProvisioner");
        int migrationResolution = configuration.indexOf("analyticsFlywayProvider.getIfAvailable()", runnerStart);
        int provisioning = configuration.indexOf("provisioner.provision(properties)", runnerStart);

        assertThat(runnerStart).isGreaterThanOrEqualTo(0);
        assertThat(configuration.substring(runnerStart))
                .contains("ObjectProvider<Flyway> analyticsFlywayProvider");
        assertThat(migrationResolution).isGreaterThanOrEqualTo(0);
        assertThat(provisioning).isGreaterThan(migrationResolution);
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
