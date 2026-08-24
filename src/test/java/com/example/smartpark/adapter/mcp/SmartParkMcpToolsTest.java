package com.example.smartpark.adapter.mcp;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.energy.EnergyPort;
import org.junit.jupiter.api.Test;

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
        var result = tools.searchKnowledge("e", "ALERT_OPERATIONS");
        assertThat(result.ok()).isTrue();
        assertThat(result.data().matches()).hasSizeLessThanOrEqualTo(5).isSortedAccordingTo(
                java.util.Comparator.comparingDouble(McpToolResults.KnowledgeMatchData::score).reversed().thenComparing(McpToolResults.KnowledgeMatchData::documentId));
    }

    @Test void hidesUnexpectedExceptionDetails() {
        EnergyPort failing = id -> { throw new IllegalStateException("private meter value for " + id); };
        var result = new SmartParkMcpTools(fixture.alerts(), failing, fixture.knowledge()).lookupEnergy("DEV-ENERGY-001");
        assertThat(result.error()).isEqualTo(McpToolResults.internalError());
        assertThat(result.toString()).doesNotContain("private meter value", "DEV-ENERGY-001");
    }
}
