package com.example.smartpark.showcase;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseComposeConfigurationTest {

    @Test
    void showcaseOverlayEnablesEveryOnlineModeAndBothLocalOrigins() throws Exception {
        String overlay = Files.readString(Path.of("compose.showcase.yaml"));

        assertThat(overlay)
                .contains("SMARTPARK_KNOWLEDGE_MODE: rag")
                .contains("SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope")
                .contains("SMARTPARK_VOICE_ENABLED: \"true\"")
                .contains("SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173")
                .doesNotContain("AI_DASHSCOPE_API_KEY:")
                .doesNotContain("SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD:")
                .doesNotContain("SMARTPARK_ANALYTICS_DB_RO_PASSWORD:");
    }

    @Test
    void defaultComposeRemainsOfflineAndApplicationMapsThePreflightTimeout() throws Exception {
        assertThat(Files.readString(Path.of("compose.yaml")))
                .contains("SPRING_AI_DASHSCOPE_ENABLED: \"false\"")
                .contains("SMARTPARK_ANALYTICS_ENABLED: \"false\"")
                .doesNotContain("SMARTPARK_VOICE_ENABLED: \"true\"");
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("preflight-timeout: ${SMARTPARK_SHOWCASE_PREFLIGHT_TIMEOUT:90s}");
    }
}
