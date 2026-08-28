package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsComposeSecurityTest {

    @Test
    void parserIsInternalNonRootAndBackendWaitsForHealth() throws Exception {
        String compose = Files.readString(Path.of("compose.analytics.yaml"));
        String dockerfile = Files.readString(Path.of("time-parser/Dockerfile"));

        assertThat(compose).contains("analytics-time-parser", "condition: service_healthy",
                "read_only: true", "no-new-privileges:true", "cap_drop:", "expose:", "\"8081\"",
                "analytics-internal:", "internal: true");
        assertThat(compose).doesNotContain("8081:8081", "- \"127.0.0.1:8081:8081\"");
        assertThat(dockerfile).contains("USER parser", "--require-hashes", "--port", "8081");
    }
}
