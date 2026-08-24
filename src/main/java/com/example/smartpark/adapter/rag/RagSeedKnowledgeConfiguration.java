package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
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
                seed(KnowledgeDomain.CUSTOMER_SERVICE, "KB-PARKING-001", "Visitor parking guide", List.of("parking", "visitor", "停车")),
                seed(KnowledgeDomain.CUSTOMER_SERVICE, "KB-VISITOR-001", "Visitor access guide", List.of("visitor", "appointment", "访客")),
                seed(KnowledgeDomain.CUSTOMER_SERVICE, "KB-REPAIR-001", "Facility repair intake guide", List.of("repair", "maintenance", "报修")),
                seed(KnowledgeDomain.ALERT_OPERATIONS, "KB-HVAC-001", "HVAC temperature response", List.of("temperature", "hvac", "暖通")),
                seed(KnowledgeDomain.ALERT_OPERATIONS, "KB-POWER-001", "Power emergency runbook", List.of("power", "breaker", "配电")),
                seed(KnowledgeDomain.ALERT_OPERATIONS, "KB-ENERGY-001", "Energy anomaly response", List.of("energy", "consumption", "baseline", "能耗")),
                seed(KnowledgeDomain.ALERT_OPERATIONS, "KB-ACCESS-001", "Access anomaly response", List.of("access", "security", "门禁")),
                seed(KnowledgeDomain.ALERT_OPERATIONS, "KB-PUMP-001", "Pump room leak response", List.of("pump", "leak", "water", "水泵")));
    }

    private static KnowledgeDocument seed(KnowledgeDomain domain, String id, String title, List<String> tags) {
        String path = "knowledge/" + id + ".md";
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.isBlank()) throw new IllegalStateException("RAG seed resource was blank: " + path);
            return new KnowledgeDocument(id, domain, title, content, tags, UPDATED_AT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load RAG seed resource: " + path, exception);
        }
    }
}
