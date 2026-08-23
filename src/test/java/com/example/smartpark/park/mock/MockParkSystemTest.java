package com.example.smartpark.park.mock;

import com.example.smartpark.model.Alert;
import com.example.smartpark.model.RiskLevel;
import com.example.smartpark.model.WorkOrder;
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

class MockParkSystemTest {

    private MockParkSystem mockParkSystem;

    @BeforeEach
    void setUp() {
        mockParkSystem = new MockParkSystem();
        mockParkSystem.reset();
    }

    @Test
    void lowRiskTemperatureAlertIsAvailable() {
        Alert alert = mockParkSystem.getAlert("ALT-TEMP-001");

        assertThat(alert.riskHint()).isEqualTo(RiskLevel.LOW);
        assertThat(alert.deviceId()).isEqualTo("DEV-HVAC-001");
    }

    @Test
    void highRiskPowerAlertRequiresTheHighRiskFixture() {
        Alert alert = mockParkSystem.getAlert("ALT-POWER-001");

        assertThat(alert.riskHint()).isEqualTo(RiskLevel.HIGH);
        assertThat(mockParkSystem.findHistory("DEV-POWER-001")).isNotEmpty();
    }

    @Test
    void creatingTheSameWorkflowTwiceIsIdempotent() {
        WorkOrder first = mockParkSystem.create("wf-1", "ALT-TEMP-001", "temperature anomaly");
        WorkOrder second = mockParkSystem.create("wf-1", "ALT-TEMP-001", "temperature anomaly");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(mockParkSystem.findByWorkflowId("wf-1")).hasSize(1);
        assertThat(first.approvalDecision()).isEqualTo(Optional.empty());
    }

    @Test
    void resetClearsCreatedWorkOrdersAndRestoresFixtureState() {
        WorkOrder created = mockParkSystem.create("wf-reset", "ALT-TEMP-001", "temperature anomaly");

        mockParkSystem.reset();

        assertThat(mockParkSystem.findByWorkflowId("wf-reset")).isEmpty();
        assertThat(mockParkSystem.getAlert("ALT-TEMP-001").riskHint()).isEqualTo(RiskLevel.LOW);

        WorkOrder recreated = mockParkSystem.create("wf-reset", "ALT-TEMP-001", "temperature anomaly");

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
                    return mockParkSystem.create("wf-concurrent", "ALT-TEMP-001", "temperature anomaly");
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
            assertThat(mockParkSystem.findByWorkflowId("wf-concurrent")).hasSize(1);
            assertThat(mockParkSystem.findByWorkflowId("wf-concurrent").get(0).approvalDecision()).isEqualTo(Optional.empty());
        } finally {
            executor.shutdownNow();
        }
    }
}
