package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class RagSeedKnowledgeConfiguration {
    private static final Instant UPDATED_AT = Instant.parse("2026-08-20T00:00:00Z");

    @Bean
    List<KnowledgeDocument> ragSeedDocuments() {
        return List.of(
                seed("KB-PARKING-001", "Visitor parking guide", List.of("parking", "visitor", "停车")),
                seed("KB-VISITOR-001", "Visitor access guide", List.of("visitor", "appointment", "访客")),
                seed("KB-ENERGY-001", "Tenant energy service guide", List.of("energy", "billing", "能耗")),
                seed("KB-REPAIR-001", "Facility repair intake guide", List.of("repair", "maintenance", "报修")));
    }

    private static KnowledgeDocument seed(String id, String title, List<String> tags) {
        String path = "knowledge/" + id + ".md";
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.isBlank()) throw new IllegalStateException("RAG seed resource was blank: " + path);
            return new KnowledgeDocument(id, title, content, tags, UPDATED_AT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load RAG seed resource: " + path, exception);
        }
    }
}
