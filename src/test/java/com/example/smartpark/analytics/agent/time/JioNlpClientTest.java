package com.example.smartpark.analytics.agent.time;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JioNlpClientTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsContractAndValidatesProviderVersionAndEcho() {
        server.createContext("/v1/time-intents:resolve", exchange -> respond(exchange, 200, """
                {"provider":"jionlp","providerVersion":"1.5.29",
                 "referenceInstant":"2026-08-25T00:00:00Z","timezone":"Asia/Shanghai",
                 "status":"PARSED","mentions":[{"text":"上周","start":0,"end":2,
                 "type":"time_span","definition":"accurate",
                 "fromInclusive":"2026-08-17T00:00:00Z","toExclusive":"2026-08-24T00:00:00Z","empty":false}],
                 "reasonCode":null}"""));
        server.start();

        JioNlpClient client = new JioNlpClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(2), 32_768, "jionlp", "1.5.29");

        TimeParserResponse response = client.resolve(new TimeParserRequest(
                "上周能耗", "2026-08-25T00:00:00Z", "Asia/Shanghai", java.util.List.of()));

        assertThat(response.status()).isEqualTo("PARSED");
        assertThat(response.provider()).isEqualTo("jionlp");
        assertThat(response.mentions()).hasSize(1);
    }

    @Test
    void rejectsProviderVersionMismatchAndHttpFailures() {
        server.createContext("/v1/time-intents:resolve", exchange -> respond(exchange, 200,
                "{\"provider\":\"other\",\"providerVersion\":\"1\",\"referenceInstant\":\"2026-08-25T00:00:00Z\",\"timezone\":\"Asia/Shanghai\",\"status\":\"NONE\",\"mentions\":[]}"));
        server.start();

        JioNlpClient client = new JioNlpClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(2), 32_768, "jionlp", "1.5.29");

        assertThatThrownBy(() -> client.resolve(new TimeParserRequest(
                "上周能耗", "2026-08-25T00:00:00Z", "Asia/Shanghai", java.util.List.of())))
                .isInstanceOf(TimeParserInvalidResponseException.class);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
