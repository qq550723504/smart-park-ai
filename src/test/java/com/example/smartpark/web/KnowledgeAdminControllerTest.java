package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeAdminControllerTest {

    @Test
    void createResponsePreservesTheExplicitKnowledgeDomain() {
        KnowledgeAdminPort knowledge = mock(KnowledgeAdminPort.class);
        KnowledgeDocument saved = new KnowledgeDocument(
                "KD-CUSTOMER-001", KnowledgeDomain.CUSTOMER_SERVICE, "Customer guide", "safe content",
                List.of("customer"), Instant.EPOCH);
        when(knowledge.save(any(KnowledgeDocument.class))).thenReturn(saved);
        KnowledgeAdminController controller = new KnowledgeAdminController(
                knowledge, new AuditTrail(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        KnowledgeAdminController.KnowledgeMetadataResponse response = controller.create(
                new KnowledgeAdminController.KnowledgeCreateRequest(
                        "KD-CUSTOMER-001", KnowledgeDomain.CUSTOMER_SERVICE, "Customer guide", "safe content", List.of("customer")),
                "ADMIN");

        assertThat(response.domain()).isEqualTo(KnowledgeDomain.CUSTOMER_SERVICE);
    }
}
