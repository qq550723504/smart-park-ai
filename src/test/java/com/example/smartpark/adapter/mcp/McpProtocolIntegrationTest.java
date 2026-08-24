package com.example.smartpark.adapter.mcp;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.dashscope.enabled=false", "smartpark.mcp.enabled=true"})
@Import(McpProtocolIntegrationTest.ProtocolKnowledgeConfiguration.class)
class McpProtocolIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @LocalServerPort private int port;

    @Test void listsAndCallsAllSafeToolsOverStreamableHttp() throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("smartpark_lookup_alert", "smartpark_lookup_energy", "smartpark_search_knowledge");

            JsonNode alert = successfulText(client.callTool(new McpSchema.CallToolRequest(
                    "smartpark_lookup_alert", Map.of("alertId", "ALT-ENERGY-001"))));
            assertThat(fieldNames(alert.path("data"))).containsExactlyInAnyOrder(
                    "alertId", "parkId", "buildingId", "deviceId", "classification", "riskHint", "occurredAt");

            JsonNode energy = successfulText(client.callTool(new McpSchema.CallToolRequest(
                    "smartpark_lookup_energy", Map.of("meterId", "DEV-ENERGY-001"))));
            assertThat(fieldNames(energy.path("data"))).containsExactlyInAnyOrder(
                    "meterId", "parkId", "buildingId", "measuredAt", "currentKwh", "baselineKwh",
                    "peakDemandKw", "varianceKwh", "varianceRatio");

            String callerControlledSecret = "employee@example.com token-should-not-echo";
            JsonNode knowledge = successfulText(client.callTool(new McpSchema.CallToolRequest(
                    "smartpark_search_knowledge",
                    Map.of("query", callerControlledSecret, "domain", "ALERT_OPERATIONS"))));
            assertThat(fieldNames(knowledge.path("data"))).containsExactlyInAnyOrder("domain", "matches");
            assertThat(knowledge.path("data").path("matches")).hasSize(5);
            assertThat(knowledge.path("data").path("matches").findValuesAsText("documentId"))
                    .containsExactly("KD-PROTOCOL-060", "KD-PROTOCOL-050", "KD-PROTOCOL-040",
                            "KD-PROTOCOL-030", "KD-PROTOCOL-020");
            assertThat(fieldNames(knowledge.path("data").path("matches").get(0))).containsExactlyInAnyOrder(
                    "documentId", "title", "domain", "tags", "score", "updatedAt");
            assertThat(alert.toString() + energy + knowledge)
                    .doesNotContain("summary", "evidence", "content", "identity", "approval", "workOrder",
                            "restartDevice", "setDeviceState", callerControlledSecret, "internal-body-sentinel");

            var invalid = client.callTool(new McpSchema.CallToolRequest("smartpark_search_knowledge", Map.of("query", "energy", "domain", "PRIVATE_OPERATIONS")));
            JsonNode error = JSON.readTree(((McpSchema.TextContent) invalid.content().get(0)).text());
            assertThat(error.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENT");
            assertThat(error.toString()).doesNotContain("PRIVATE_OPERATIONS");
        }
    }

    @Test
    void statelessTransportDoesNotIssueSessionIdentifier() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stateless-test","version":"1.0"}}}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
    }

    private static JsonNode successfulText(McpSchema.CallToolResult result) throws Exception {
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isNull();
        assertThat(result.content()).hasSize(1).first().isInstanceOf(McpSchema.TextContent.class);
        JsonNode json = JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertThat(json.path("ok").asBoolean()).isTrue();
        assertThat(json.path("notice").asText()).isEqualTo(McpToolResults.NOTICE);
        return json;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtocolKnowledgeConfiguration {
        @Bean
        @Primary
        KnowledgePort protocolKnowledgePort() {
            return new KnowledgePort() {
                @Override
                public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                    return List.of();
                }

                @Override
                public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                    return List.of(
                            match("KD-PROTOCOL-030", 0.3),
                            match("KD-PROTOCOL-060", 0.6),
                            match("KD-PROTOCOL-020", 0.2),
                            match("KD-PROTOCOL-050", 0.5),
                            match("KD-PROTOCOL-010", 0.1),
                            match("KD-PROTOCOL-040", 0.4));
                }
            };
        }

        private static KnowledgeMatch match(String id, double score) {
            var document = new KnowledgeDocument(id, KnowledgeDomain.ALERT_OPERATIONS,
                    "Protocol metadata " + id, "internal-body-sentinel", List.of("energy"),
                    Instant.parse("2026-08-24T00:00:00Z"));
            return new KnowledgeMatch(document, score);
        }
    }
}
