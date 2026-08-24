package com.example.smartpark.adapter.mcp;

import com.example.smartpark.SmartParkApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpFeatureGateIntegrationTest {

    @Test
    void genericSpringAiPropertyCannotEnableMcpWhenSmartParkGateIsDisabled() throws Exception {
        try (ConfigurableApplicationContext context = runApplication(false, true)) {
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
            assertThat(postInitialize(context).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void genericSpringAiPropertyCannotDisableMcpWhenSmartParkGateIsEnabled() throws Exception {
        try (ConfigurableApplicationContext context = runApplication(true, false)) {
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).hasSize(1);
            assertThat(postInitialize(context).statusCode()).isEqualTo(200);
        }
    }

    private static ConfigurableApplicationContext runApplication(boolean smartParkEnabled,
            boolean genericSpringAiEnabled) {
        return new SpringApplicationBuilder(SmartParkApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.ai.dashscope.enabled=false",
                        "--smartpark.mcp.enabled=" + smartParkEnabled,
                        "--spring.ai.mcp.server.enabled=" + genericSpringAiEnabled);
    }

    private static HttpResponse<String> postInitialize(ConfigurableApplicationContext context) throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"feature-gate-test","version":"1.0"}}}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
