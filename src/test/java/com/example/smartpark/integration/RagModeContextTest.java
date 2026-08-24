package com.example.smartpark.integration;

import com.example.smartpark.adapter.mock.MockKnowledgeAdapter;
import com.example.smartpark.adapter.rag.RagKnowledgeAdapter;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=false",
        "smartpark.knowledge.mode=rag",
        "smartpark.customer-service.answer-mode=mock"
})
class RagModeContextTest {
    @Autowired KnowledgePort knowledgePort;
    @Autowired KnowledgeAdminPort knowledgeAdminPort;

    @Test
    void ragModeStartsWithFakeEmbeddingAndUsesSameIndexForAdminUpdates() {
        assertThat(knowledgePort).isInstanceOf(RagKnowledgeAdapter.class);
        assertThat(knowledgePort).isSameAs(knowledgeAdminPort);
        assertThat(knowledgeAdminPort.list()).extracting(item -> item.document().id())
                .contains("KB-PARKING-001", "KB-VISITOR-001", "KB-ENERGY-001", "KB-REPAIR-001");
        assertThat(knowledgePort.search(KnowledgeDomain.ALERT_OPERATIONS, "temperature"))
                .extracting(document -> document.id()).contains("KB-HVAC-001");
        assertThat(knowledgePort.search(KnowledgeDomain.CUSTOMER_SERVICE, "temperature"))
                .extracting(document -> document.id()).doesNotContain("KB-HVAC-001");
        assertThat(knowledgePort.search(KnowledgeDomain.CUSTOMER_SERVICE, "energy"))
                .extracting(document -> document.id()).contains("KB-CUSTOMER-ENERGY-001");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEmbeddingConfiguration {
        @Bean @Primary
        EmbeddingModel embeddingModel() {
            return new EmbeddingModel() {
                @Override public float[] embed(Document document) { return embed(document.getText()); }
                @Override public float[] embed(String text) {
                    int hash = text == null ? 0 : text.hashCode();
                    return new float[] { 1f, (hash & 255) / 255f, .1f };
                }
                @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
