package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagSeedKnowledgeConfigurationTest {
    @Test
    void seedDocumentsContainOnlySafeKnowledgeContentAndExpectedCustomerDomains() {
        List<KnowledgeDocument> documents = new RagSeedKnowledgeConfiguration().ragSeedDocuments();
        assertThat(documents).extracting(KnowledgeDocument::id)
                .contains("KB-PARKING-001", "KB-VISITOR-001", "KB-ENERGY-001", "KB-REPAIR-001",
                        "KB-HVAC-001", "KB-POWER-001", "KB-ACCESS-001", "KB-PUMP-001");
        assertThat(documents.stream().filter(document -> document.domain() == KnowledgeDomain.ALERT_OPERATIONS)
                .map(KnowledgeDocument::id))
                .containsExactlyInAnyOrder("KB-HVAC-001", "KB-POWER-001", "KB-ACCESS-001", "KB-PUMP-001");
        assertThat(documents).allMatch(document -> document.content().length() <= 2_000);
        assertThat(documents).noneMatch(document -> document.content().contains("身份证原件"));
    }
}
