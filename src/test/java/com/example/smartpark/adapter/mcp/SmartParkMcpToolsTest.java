package com.example.smartpark.adapter.mcp;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SmartParkMcpToolsTest {
    private final MockParkFixture fixture = new MockParkFixture();
    private final SmartParkMcpTools tools = new SmartParkMcpTools(fixture.alerts(), fixture.energy(), fixture.knowledge());

    @Test void returnsAllowlistedAlertMetadata() {
        var result = tools.lookupAlert(" ALT-ENERGY-001 ");
        assertThat(result.ok()).isTrue();
        assertThat(result.data().alertId()).isEqualTo("ALT-ENERGY-001");
        assertThat(result.data().deviceId()).isEqualTo("DEV-ENERGY-001");
        assertThat(result.data().classification()).isEqualTo("ENERGY");
        assertThat(result.data().riskHint()).isEqualTo("HIGH");
    }

    @Test void rejectsInvalidAlertIdWithoutCallingThePort() {
        AlertPort port = mock(AlertPort.class);
        var result = new SmartParkMcpTools(port, fixture.energy(), fixture.knowledge()).lookupAlert("missing-alert");
        assertThat(result.error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(port);
    }

    @Test void mapsUnknownValidAlertToSafeNotFound() {
        var result = tools.lookupAlert("ALT-UNKNOWN-001");
        assertThat(result.error()).isEqualTo(McpToolResults.notFound());
    }

    @Test void returnsEnergyReadingAndDerivedVariance() {
        var data = tools.lookupEnergy("DEV-ENERGY-001").data();
        assertThat(data.currentKwh()).isEqualTo(138.0);
        assertThat(data.baselineKwh()).isEqualTo(100.0);
        assertThat(data.varianceKwh()).isEqualTo(38.0);
        assertThat(data.varianceRatio()).isEqualTo(0.38);
    }

    @Test void rejectsInvalidKnowledgeInputsWithoutCallingPort() {
        var knowledge = mock(com.example.smartpark.port.knowledge.KnowledgePort.class);
        var subject = new SmartParkMcpTools(fixture.alerts(), fixture.energy(), knowledge);
        assertThat(subject.searchKnowledge("", "ALERT_OPERATIONS").error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
        assertThat(subject.searchKnowledge("x".repeat(501), "ALERT_OPERATIONS").error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
        assertThat(subject.searchKnowledge("energy", "PRIVATE_OPERATIONS").error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(knowledge);
    }

    @Test void limitsAndOrdersKnowledgeMetadata() {
        KnowledgePort knowledge = new KnowledgePort() {
            @Override
            public List<KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                return List.of();
            }

            @Override
            public List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                return List.of(
                        match("KD-TEST-030", 0.3),
                        match("KD-TEST-060", 0.6),
                        match("KD-TEST-020", 0.2),
                        match("KD-TEST-050", 0.5),
                        match("KD-TEST-010", 0.1),
                        match("KD-TEST-040", 0.4));
            }
        };
        var subject = new SmartParkMcpTools(fixture.alerts(), fixture.energy(), knowledge);

        var result = subject.searchKnowledge("energy", "ALERT_OPERATIONS");

        assertThat(result.ok()).isTrue();
        assertThat(result.data().matches())
                .extracting(McpToolResults.KnowledgeMatchData::documentId)
                .containsExactly("KD-TEST-060", "KD-TEST-050", "KD-TEST-040", "KD-TEST-030", "KD-TEST-020");
        assertThat(result.data().matches())
                .extracting(McpToolResults.KnowledgeMatchData::score)
                .containsExactly(0.6, 0.5, 0.4, 0.3, 0.2);
    }

    @Test void hidesUnexpectedExceptionDetails() {
        EnergyPort failing = id -> { throw new IllegalStateException("private meter value for " + id); };
        var result = new SmartParkMcpTools(fixture.alerts(), failing, fixture.knowledge()).lookupEnergy("DEV-ENERGY-001");
        assertThat(result.error()).isEqualTo(McpToolResults.internalError());
        assertThat(result.toString()).doesNotContain("private meter value", "DEV-ENERGY-001");
    }

    private static KnowledgeMatch match(String id, double score) {
        var document = new KnowledgeDocument(id, KnowledgeDomain.ALERT_OPERATIONS, "Energy test document",
                "Internal body must not be returned.", List.of("energy"), Instant.parse("2026-08-24T00:00:00Z"));
        return new KnowledgeMatch(document, score);
    }
}
