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
        String output = JSON.writeValueAsString(tools.lookupAlert("ALT-ENERGY-001"))
                + JSON.writeValueAsString(tools.searchKnowledge("energy", "ALERT_OPERATIONS"));
        assertThat(output).doesNotContain("summary", "evidence", "diagnosis", "approval", "workOrder", "content", "embedding", "vector");
    }

    @Test void mcpDtoFieldsAreExactAllowlists() {
        assertThat(componentNames(McpToolResults.AlertData.class)).containsExactly("alertId", "parkId", "buildingId", "deviceId", "classification", "riskHint", "occurredAt");
        assertThat(componentNames(McpToolResults.KnowledgeMatchData.class)).containsExactly("documentId", "title", "domain", "tags", "score", "updatedAt");
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
