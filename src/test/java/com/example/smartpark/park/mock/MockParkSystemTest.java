package com.example.smartpark.park.mock;

import com.example.smartpark.model.Alert;
import com.example.smartpark.model.RiskLevel;
import com.example.smartpark.model.WorkOrder;
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
    }
}
