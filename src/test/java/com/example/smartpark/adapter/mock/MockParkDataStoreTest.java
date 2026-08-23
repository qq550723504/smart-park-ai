package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.common.WorkOrder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockParkDataStoreTest {

    @Test
    void storeKeepsItsMutationSurfacePackagePrivateAndDoesNotExposeMutableContainers() {
        assertThat(Modifier.isPublic(MockParkDataStore.class.getDeclaredConstructors()[0].getModifiers())).isFalse();

        List<String> internalOperationNames = List.of(
                "reset",
                "getDevice",
                "getLatestEnergyReading",
                "getAlert",
                "findHistory",
                "findByWorkflowId",
                "buildWorkOrder",
                "search");
        assertThat(Arrays.stream(MockParkDataStore.class.getDeclaredMethods())
                .filter(method -> internalOperationNames.contains(method.getName()))
                .allMatch(method -> !Modifier.isPublic(method.getModifiers())))
                .isTrue();

        assertThat(Arrays.stream(MockParkDataStore.class.getDeclaredMethods())
                .map(method -> method.getName())
                .toList())
                .doesNotContain(
                        "devices",
                        "energyReadings",
                        "alerts",
                        "historyByDevice",
                        "knowledgeDocuments",
                        "workOrdersByWorkflowId",
                        "workOrderSequence");
    }

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
