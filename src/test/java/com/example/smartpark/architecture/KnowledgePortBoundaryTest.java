package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePortBoundaryTest {
    @Test
    void knowledgePortRequiresAnExplicitDomainAndOneCanonicalMatchType() throws Exception {
        Path port = Path.of("src/main/java/com/example/smartpark/port/knowledge/KnowledgePort.java");
        String source = Files.readString(port, StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("default List<KnowledgeDocument> search(String query)");
        assertThat(source).doesNotContain("rankedSearch(String query)");
        assertThat(Files.exists(Path.of("src/main/java/com/example/smartpark/port/knowledge/KnowledgeMatch.java")))
                .as("legacy duplicate KnowledgeMatch type")
                .isFalse();
    }
}
