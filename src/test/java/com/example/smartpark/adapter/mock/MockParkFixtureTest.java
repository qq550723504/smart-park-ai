package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockParkFixtureTest {

    private MockParkFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new MockParkFixture();
        fixture.reset();
    }

    @Test
    void lowRiskTemperatureAlertIsAvailable() {
        Alert alert = fixture.alerts().getAlert("ALT-TEMP-001");

        assertThat(alert.riskHint()).isEqualTo(RiskLevel.LOW);
        assertThat(alert.deviceId()).isEqualTo("DEV-HVAC-001");
    }

    @Test
    void highRiskPowerAlertRequiresTheHighRiskFixture() {
        Alert alert = fixture.alerts().getAlert("ALT-POWER-001");

        assertThat(alert.riskHint()).isEqualTo(RiskLevel.HIGH);
        assertThat(fixture.alerts().findHistory("DEV-POWER-001")).isNotEmpty();
    }

    @Test
    void energyAlertHasLatestConsumptionAboveItsBaseline() {
        Alert alert = fixture.alerts().getAlert("ALT-ENERGY-001");

        assertThat(alert.classification()).isEqualTo(com.example.smartpark.model.alert.AlertClassification.ENERGY);
        assertThat(fixture.energy().getLatestEnergyReading("DEV-ENERGY-001").varianceRatio())
                .isEqualTo(0.38);
    }

    @Test
    void creatingTheSameWorkflowTwiceIsIdempotent() {
        WorkOrder first = fixture.workOrders().create("wf-1", "ALT-TEMP-001", "temperature anomaly");
        WorkOrder second = fixture.workOrders().create("wf-1", "ALT-TEMP-001", "temperature anomaly");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(fixture.workOrders().findByWorkflowId("wf-1")).hasSize(1);
        assertThat(first.approvalDecision()).isEqualTo(Optional.empty());
    }

    @Test
    void resetClearsCreatedWorkOrdersAndRestoresFixtureState() {
        WorkOrder created = fixture.workOrders().create("wf-reset", "ALT-TEMP-001", "temperature anomaly");

        fixture.reset();

        assertThat(fixture.workOrders().findByWorkflowId("wf-reset")).isEmpty();
        assertThat(fixture.alerts().getAlert("ALT-TEMP-001").riskHint()).isEqualTo(RiskLevel.LOW);

        WorkOrder recreated = fixture.workOrders().create("wf-reset", "ALT-TEMP-001", "temperature anomaly");

        assertThat(recreated.id()).isEqualTo(created.id());
        assertThat(recreated.approvalDecision()).isEqualTo(Optional.empty());
    }

    @Test
    void concurrentDuplicateCreatesLeaveExactlyOneStoredOrder() throws Exception {
        int callers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<WorkOrder>> tasks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.workOrders().create("wf-concurrent", "ALT-TEMP-001", "temperature anomaly");
                });
            }

            List<Future<WorkOrder>> futures = new ArrayList<>();
            for (Callable<WorkOrder> task : tasks) {
                futures.add(executor.submit(task));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<WorkOrder> results = new ArrayList<>();
            for (Future<WorkOrder> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            Set<String> ids = results.stream().map(WorkOrder::id).collect(java.util.stream.Collectors.toSet());
            assertThat(ids).hasSize(1);
            assertThat(fixture.workOrders().findByWorkflowId("wf-concurrent")).hasSize(1);
            assertThat(fixture.workOrders().findByWorkflowId("wf-concurrent").get(0).approvalDecision()).isEqualTo(Optional.empty());
        } finally {
            executor.shutdownNow();
        }
    }
}
