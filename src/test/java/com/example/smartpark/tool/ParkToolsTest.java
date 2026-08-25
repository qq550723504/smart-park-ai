package com.example.smartpark.tool;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkOrderStatus;
import com.example.smartpark.port.workorder.WorkOrderPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ParkToolsTest {

    @Test
    void deviceLookupReturnsStructuredErrorForBlankDeviceId() {
        DeviceQueryTool tool = new DeviceQueryTool(new MockParkFixture().devices());

        assertThatCode(() -> tool.lookupDeviceStatus("   ")).doesNotThrowAnyException();
        DeviceQueryTool.DeviceLookupResult result = tool.lookupDeviceStatus("   ");

        assertThat(result.device()).isNull();
        assertThat(result.error()).contains("deviceId");
    }

    @Test
    void deviceLookupReturnsStructuredErrorForUnknownDeviceId() {
        DeviceQueryTool tool = new DeviceQueryTool(new MockParkFixture().devices());

        DeviceQueryTool.DeviceLookupResult result = tool.lookupDeviceStatus("DEV-MISSING");

        assertThat(result.device()).isNull();
        assertThat(result.error()).contains("Unknown device");
    }

    @Test
    void alertLookupReturnsStructuredErrorForBlankAlertId() {
        AlertQueryTool tool = new AlertQueryTool(new MockParkFixture().alerts());

        assertThatCode(() -> tool.lookupAlert("   ")).doesNotThrowAnyException();
        AlertQueryTool.AlertLookupResult result = tool.lookupAlert("   ");

        assertThat(result.alert()).isNull();
        assertThat(result.error()).contains("alertId");
    }

    @Test
    void alertHistoryReturnsStructuredErrorForBlankDeviceId() {
        AlertQueryTool tool = new AlertQueryTool(new MockParkFixture().alerts());

        assertThatCode(() -> tool.lookupAlertHistory(" ")).doesNotThrowAnyException();
        AlertQueryTool.AlertHistoryResult result = tool.lookupAlertHistory(" ");

        assertThat(result.alerts()).isEmpty();
        assertThat(result.error()).contains("deviceId");
    }

    @Test
    void workOrderLookupReturnsStructuredErrorForBlankWorkflowId() {
        WorkOrderTool tool = new WorkOrderTool(new MockParkFixture().workOrders());

        assertThatCode(() -> tool.lookupWorkOrders(" ")).doesNotThrowAnyException();
        WorkOrderTool.WorkOrderLookupResult result = tool.lookupWorkOrders(" ");

        assertThat(result.workOrders()).isEmpty();
        assertThat(result.error()).contains("workflowId");
    }

    @Test
    void workOrderLookupProjectsApprovalWithoutTheIdempotencyKey() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        WorkOrder internal = new WorkOrder("WO-1", "wf-1", "P-1", "B-1", "D-1", "A-1",
                "internal summary", RiskLevel.HIGH, WorkOrderStatus.IN_PROGRESS,
                Optional.of(new ApprovalDecision(ApprovalDecision.Decision.APPROVED,
                        "operator", "approved", "secret-approval-key", now)),
                List.of("internal evidence"), now, now);
        WorkOrderPort port = new WorkOrderPort() {
            @Override public List<WorkOrder> findByWorkflowId(String workflowId) { return List.of(internal); }
            @Override public WorkOrder create(String workflowId, String alertId, String summary) { return internal; }
        };

        WorkOrderTool.WorkOrderLookupResult result = new WorkOrderTool(port).lookupWorkOrders("wf-1");
        var projection = result.workOrders().get(0);

        assertThat(projection.approval().toString()).doesNotContain("secret-approval-key");
        assertThat(java.util.Arrays.stream(WorkOrderTool.PublicWorkOrder.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("approvalDecision", "idempotencyKey");
        assertThat(projection.approval().decision()).isEqualTo("APPROVED");
    }

    @Test
    void createWorkOrderReturnsStructuredErrorForBlankSummary() {
        WorkOrderTool tool = new WorkOrderTool(new MockParkFixture().workOrders());

        assertThatCode(() -> tool.createWorkOrder("wf-1", "ALT-TEMP-001", " ")).doesNotThrowAnyException();
        WorkOrderTool.WorkOrderCreateResult result = tool.createWorkOrder("wf-1", "ALT-TEMP-001", " ");

        assertThat(result.workOrder()).isNull();
        assertThat(result.error()).contains("summary");
    }

    @Test
    void knowledgeSearchReturnsStructuredErrorForBlankQuery() {
        ParkKnowledgeTool tool = new ParkKnowledgeTool(new MockParkFixture().knowledge());

        assertThatCode(() -> tool.searchParkKnowledge(" ")).doesNotThrowAnyException();
        ParkKnowledgeTool.KnowledgeSearchResult result = tool.searchParkKnowledge(" ");

        assertThat(result.documents()).isEmpty();
        assertThat(result.error()).contains("query");
    }
}
