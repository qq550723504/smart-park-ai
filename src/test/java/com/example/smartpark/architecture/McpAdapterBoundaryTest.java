package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class McpAdapterBoundaryTest {
    private static final List<String> FORBIDDEN = List.of("com.example.smartpark.web", "com.example.smartpark.agent", "com.example.smartpark.workflow", "com.example.smartpark.audit", "com.example.smartpark.feedback", "KnowledgeAdminPort", "WorkOrderPort", "com.example.smartpark.adapter.mock", "com.example.smartpark.adapter.rag", "@Component", "@McpTool");

    @Test void mcpAdapterUsesOnlyApprovedBoundaries() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/com/example/smartpark/adapter/mcp"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(path)).as("MCP source %s", path).doesNotContain(FORBIDDEN.toArray(String[]::new));
            }
        }
    }
}
