package com.example.smartpark.adapter.mock;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockAdapterBoundaryTest {

    @Test
    void eachMockAdapterImplementsOnlyItsCapabilityPort() {
        assertThat(AlertPort.class.isAssignableFrom(MockAlertAdapter.class)).isTrue();
        assertThat(DevicePort.class.isAssignableFrom(MockAlertAdapter.class)).isFalse();
        assertThat(EnergyPort.class.isAssignableFrom(MockAlertAdapter.class)).isFalse();
        assertThat(KnowledgePort.class.isAssignableFrom(MockAlertAdapter.class)).isFalse();
        assertThat(WorkOrderPort.class.isAssignableFrom(MockAlertAdapter.class)).isFalse();

        assertThat(DevicePort.class.isAssignableFrom(MockDeviceAdapter.class)).isTrue();
        assertThat(AlertPort.class.isAssignableFrom(MockDeviceAdapter.class)).isFalse();
        assertThat(EnergyPort.class.isAssignableFrom(MockDeviceAdapter.class)).isFalse();
        assertThat(KnowledgePort.class.isAssignableFrom(MockDeviceAdapter.class)).isFalse();
        assertThat(WorkOrderPort.class.isAssignableFrom(MockDeviceAdapter.class)).isFalse();

        assertThat(EnergyPort.class.isAssignableFrom(MockEnergyAdapter.class)).isTrue();
        assertThat(AlertPort.class.isAssignableFrom(MockEnergyAdapter.class)).isFalse();
        assertThat(DevicePort.class.isAssignableFrom(MockEnergyAdapter.class)).isFalse();
        assertThat(KnowledgePort.class.isAssignableFrom(MockEnergyAdapter.class)).isFalse();
        assertThat(WorkOrderPort.class.isAssignableFrom(MockEnergyAdapter.class)).isFalse();

        assertThat(KnowledgePort.class.isAssignableFrom(MockKnowledgeAdapter.class)).isTrue();
        assertThat(AlertPort.class.isAssignableFrom(MockKnowledgeAdapter.class)).isFalse();
        assertThat(DevicePort.class.isAssignableFrom(MockKnowledgeAdapter.class)).isFalse();
        assertThat(EnergyPort.class.isAssignableFrom(MockKnowledgeAdapter.class)).isFalse();
        assertThat(WorkOrderPort.class.isAssignableFrom(MockKnowledgeAdapter.class)).isFalse();

        assertThat(WorkOrderPort.class.isAssignableFrom(MockWorkOrderAdapter.class)).isTrue();
        assertThat(AlertPort.class.isAssignableFrom(MockWorkOrderAdapter.class)).isFalse();
        assertThat(DevicePort.class.isAssignableFrom(MockWorkOrderAdapter.class)).isFalse();
        assertThat(EnergyPort.class.isAssignableFrom(MockWorkOrderAdapter.class)).isFalse();
        assertThat(KnowledgePort.class.isAssignableFrom(MockWorkOrderAdapter.class)).isFalse();
    }

    @Test
    void energyAdapterReturnsTheSeededEnergyReading() {
        EnergyPort energy = new MockEnergyAdapter(new MockParkDataStore());

        assertThat(energy.getLatestEnergyReading("DEV-ENERGY-001").varianceRatio()).isEqualTo(0.38);
    }
}
