package com.example.smartpark.tool;

import com.example.smartpark.park.mock.MockParkSystem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ParkToolsTest {

    @Test
    void deviceLookupReturnsStructuredErrorForBlankDeviceId() {
        DeviceQueryTool tool = new DeviceQueryTool(new MockParkSystem());

        assertThatCode(() -> tool.lookupDeviceStatus("   ")).doesNotThrowAnyException();
        DeviceQueryTool.DeviceLookupResult result = tool.lookupDeviceStatus("   ");

        assertThat(result.device()).isNull();
        assertThat(result.error()).contains("deviceId");
    }

    @Test
    void deviceLookupReturnsStructuredErrorForUnknownDeviceId() {
        DeviceQueryTool tool = new DeviceQueryTool(new MockParkSystem());

        DeviceQueryTool.DeviceLookupResult result = tool.lookupDeviceStatus("DEV-MISSING");

        assertThat(result.device()).isNull();
        assertThat(result.error()).contains("Unknown device");
    }

    @Test
    void alertLookupReturnsStructuredErrorForBlankAlertId() {
        AlertQueryTool tool = new AlertQueryTool(new MockParkSystem());

        assertThatCode(() -> tool.lookupAlert("   ")).doesNotThrowAnyException();
        AlertQueryTool.AlertLookupResult result = tool.lookupAlert("   ");

        assertThat(result.alert()).isNull();
        assertThat(result.error()).contains("alertId");
    }

    @Test
    void alertHistoryReturnsStructuredErrorForBlankDeviceId() {
        AlertQueryTool tool = new AlertQueryTool(new MockParkSystem());

        assertThatCode(() -> tool.lookupAlertHistory(" ")).doesNotThrowAnyException();
        AlertQueryTool.AlertHistoryResult result = tool.lookupAlertHistory(" ");

        assertThat(result.alerts()).isEmpty();
        assertThat(result.error()).contains("deviceId");
    }

    @Test
    void workOrderLookupReturnsStructuredErrorForBlankWorkflowId() {
        WorkOrderTool tool = new WorkOrderTool(new MockParkSystem());

        assertThatCode(() -> tool.lookupWorkOrders(" ")).doesNotThrowAnyException();
        WorkOrderTool.WorkOrderLookupResult result = tool.lookupWorkOrders(" ");

        assertThat(result.workOrders()).isEmpty();
        assertThat(result.error()).contains("workflowId");
    }

    @Test
    void createWorkOrderReturnsStructuredErrorForBlankSummary() {
        WorkOrderTool tool = new WorkOrderTool(new MockParkSystem());

        assertThatCode(() -> tool.createWorkOrder("wf-1", "ALT-TEMP-001", " ")).doesNotThrowAnyException();
        WorkOrderTool.WorkOrderCreateResult result = tool.createWorkOrder("wf-1", "ALT-TEMP-001", " ");

        assertThat(result.workOrder()).isNull();
        assertThat(result.error()).contains("summary");
    }

    @Test
    void knowledgeSearchReturnsStructuredErrorForBlankQuery() {
        ParkKnowledgeTool tool = new ParkKnowledgeTool(new MockParkSystem());

        assertThatCode(() -> tool.searchParkKnowledge(" ")).doesNotThrowAnyException();
        ParkKnowledgeTool.KnowledgeSearchResult result = tool.searchParkKnowledge(" ");

        assertThat(result.documents()).isEmpty();
        assertThat(result.error()).contains("query");
    }
}
