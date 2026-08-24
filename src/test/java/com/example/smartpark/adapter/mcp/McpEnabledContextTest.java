package com.example.smartpark.adapter.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.ai.dashscope.enabled=false", "smartpark.mcp.enabled=true"})
class McpEnabledContextTest {
    @Autowired @Qualifier("smartParkMcpToolCallbackProvider")
    private ToolCallbackProvider provider;

    @Test void exposesExactlyThreeDescribedTools() {
        var callbacks = provider.getToolCallbacks();
        Set<String> names = Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("smartpark_lookup_alert", "smartpark_lookup_energy", "smartpark_search_knowledge");
        assertThat(callbacks).allSatisfy(callback -> {
            assertThat(callback.getToolDefinition().description()).isNotBlank();
            assertThat(callback.getToolDefinition().inputSchema()).isNotBlank();
        });
        String schema = Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals("smartpark_search_knowledge"))
                .findFirst().orElseThrow().getToolDefinition().inputSchema();
        assertThat(schema).contains("query", "domain", "required");
    }
}
