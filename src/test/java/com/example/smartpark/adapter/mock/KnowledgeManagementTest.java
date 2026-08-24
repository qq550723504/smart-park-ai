package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeManagementTest {

    @Test
    void inactiveDocumentsAreExcludedFromSearchAndCanBeReactivated() {
        MockParkDataStore store = new MockParkDataStore();
        KnowledgeAdminPort knowledge = new MockKnowledgeAdapter(store);

        knowledge.setActive("KD-PARKING-001", false);
        assertThat(knowledge.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).isEmpty();
        assertThat(knowledge.list()).filteredOn(item -> item.document().id().equals("KD-PARKING-001"))
                .extracting(KnowledgeAdminPort.ManagedDocument::active).containsOnly(false);

        knowledge.setActive("KD-PARKING-001", true);
        assertThat(knowledge.search(KnowledgeDomain.CUSTOMER_SERVICE, "parking")).extracting(KnowledgeDocument::id)
                .contains("KD-PARKING-001");
    }

    @Test
    void administratorCanAddSearchableKnowledge() {
        MockParkDataStore store = new MockParkDataStore();
        KnowledgeAdminPort knowledge = new MockKnowledgeAdapter(store);
        KnowledgeDocument document = new KnowledgeDocument(
                "KD-SHUTTLE-001", KnowledgeDomain.CUSTOMER_SERVICE, "Shuttle guide", "The shuttle follows the published timetable.",
                List.of("shuttle", "班车"), Instant.parse("2026-08-23T03:00:00Z"));

        knowledge.save(document);

        assertThat(knowledge.search(KnowledgeDomain.CUSTOMER_SERVICE, "shuttle")).extracting(KnowledgeDocument::id)
                .contains("KD-SHUTTLE-001");
    }
}
