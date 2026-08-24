package com.example.smartpark.architecture;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpDependencyCompatibilityTest {

    @Test
    void mcpCoreAndWebMvcTransportUseTheSameSdkVersion() {
        String coreVersion = McpClient.class.getPackage().getImplementationVersion();
        String webMvcVersion = WebMvcStatelessServerTransport.class.getPackage().getImplementationVersion();

        assertThat(coreVersion)
                .as("MCP core and WebMVC transport must come from one coherent SDK release")
                .isNotBlank()
                .isEqualTo(webMvcVersion);
    }
}
