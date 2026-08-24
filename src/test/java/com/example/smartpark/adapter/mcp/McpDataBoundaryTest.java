package com.example.smartpark.adapter.mcp;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpDataBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test void serializedMcpResultsExcludeInternalFieldsAndBodies() throws Exception {
        var fixture = new MockParkFixture();
        var tools = new SmartParkMcpTools(fixture.alerts(), fixture.energy(), fixture.knowledge());
        String callerControlledSecret = "employee@example.com token-should-not-echo";
        String output = JSON.writeValueAsString(tools.lookupAlert("ALT-ENERGY-001"))
                + JSON.writeValueAsString(tools.searchKnowledge(callerControlledSecret, "ALERT_OPERATIONS"));
        assertThat(output).doesNotContain("summary", "evidence", "diagnosis", "approval", "workOrder", "content", "embedding", "vector");
        assertThat(output).doesNotContain(callerControlledSecret, "employee@example.com", "token-should-not-echo");
    }

    @Test void mcpDtoFieldsAreExactAllowlists() {
        assertThat(componentNames(McpToolResults.McpError.class)).containsExactly("code", "message");
        assertThat(componentNames(McpToolResults.AlertData.class)).containsExactly("alertId", "parkId", "buildingId", "deviceId", "classification", "riskHint", "occurredAt");
        assertThat(componentNames(McpToolResults.AlertLookupResult.class)).containsExactly("ok", "data", "error", "notice");
        assertThat(componentNames(McpToolResults.EnergyData.class)).containsExactly("meterId", "parkId", "buildingId", "measuredAt", "currentKwh", "baselineKwh", "peakDemandKw", "varianceKwh", "varianceRatio");
        assertThat(componentNames(McpToolResults.EnergyLookupResult.class)).containsExactly("ok", "data", "error", "notice");
        assertThat(componentNames(McpToolResults.KnowledgeMatchData.class)).containsExactly("documentId", "title", "domain", "tags", "score", "updatedAt");
        assertThat(componentNames(McpToolResults.KnowledgeData.class)).containsExactly("domain", "matches");
        assertThat(componentNames(McpToolResults.KnowledgeSearchResult.class)).containsExactly("ok", "data", "error", "notice");
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
