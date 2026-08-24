package com.example.smartpark.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.dashscope.enabled=false", "smartpark.mcp.enabled=true"})
class McpProtocolIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @LocalServerPort private int port;

    @Test void listsAndCallsOnlySafeToolsOverStreamableHttp() throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("smartpark_lookup_alert", "smartpark_lookup_energy", "smartpark_search_knowledge");

            var result = client.callTool(new McpSchema.CallToolRequest("smartpark_lookup_alert", Map.of("alertId", "ALT-ENERGY-001")));
            assertThat(result.isError()).isFalse();
            assertThat(result.structuredContent()).isNull();
            assertThat(result.content()).hasSize(1).first().isInstanceOf(McpSchema.TextContent.class);
            JsonNode json = JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
            assertThat(json.path("ok").asBoolean()).isTrue();
            assertThat(json.path("notice").asText()).isEqualTo(McpToolResults.NOTICE);
            assertThat(json.toString()).doesNotContain("summary", "evidence", "content", "identity", "approval", "workOrder", "restartDevice", "setDeviceState");

            var invalid = client.callTool(new McpSchema.CallToolRequest("smartpark_search_knowledge", Map.of("query", "energy", "domain", "PRIVATE_OPERATIONS")));
            JsonNode error = JSON.readTree(((McpSchema.TextContent) invalid.content().get(0)).text());
            assertThat(error.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENT");
            assertThat(error.toString()).doesNotContain("PRIVATE_OPERATIONS");
        }
    }
}
