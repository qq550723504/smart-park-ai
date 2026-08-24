package com.example.smartpark.architecture;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpDependencyCompatibilityTest {

    @Test
    void mcpCoreAndWebMvcTransportUseOneExpectedSdkRelease() throws IOException {
        String coreVersion = McpClient.class.getPackage().getImplementationVersion();
        String webMvcVersion = WebMvcStatelessServerTransport.class.getPackage().getImplementationVersion();

        assertThat(coreVersion)
                .as("MCP core and WebMVC transport must come from one coherent SDK release")
                .isEqualTo("0.17.0");
        assertThat(webMvcVersion).isEqualTo("0.17.0");
        assertThat(resourcesFor("io/modelcontextprotocol/client/McpClient.class")).hasSize(1);
        assertThat(resourcesFor("io/modelcontextprotocol/server/transport/WebMvcStatelessServerTransport.class")).hasSize(1);
    }

    private static List<URL> resourcesFor(String classResource) throws IOException {
        return Collections.list(McpDependencyCompatibilityTest.class.getClassLoader().getResources(classResource));
    }
}
