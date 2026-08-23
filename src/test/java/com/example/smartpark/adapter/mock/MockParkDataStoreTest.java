package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.WorkOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockParkDataStoreTest {

    @Test
    void resetRemovesCreatedWorkOrders() {
        MockParkDataStore store = new MockParkDataStore();

        store.buildWorkOrder("wf-reset", "ALT-TEMP-001", "temperature anomaly");
        store.reset();

        assertThat(store.findByWorkflowId("wf-reset")).isEmpty();
    }

    @Test
    void creatingTheSameWorkflowTwiceReturnsTheSameWorkOrderId() {
        MockParkDataStore store = new MockParkDataStore();

        WorkOrder first = store.buildWorkOrder("wf-1", "ALT-TEMP-001", "temperature anomaly");
        WorkOrder second = store.buildWorkOrder("wf-1", "ALT-TEMP-001", "temperature anomaly");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.findByWorkflowId("wf-1")).containsExactly(first);
    }
}
