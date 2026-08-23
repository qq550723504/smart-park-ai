package com.example.smartpark.tool;

import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyQueryToolTest {

    @Test
    void returnsLatestConsumptionAndVarianceForAnEnergyMeter() {
        EnergyQueryTool tool = new EnergyQueryTool(new MockParkFixture().energy());

        EnergyQueryTool.EnergyLookupResult result = tool.lookupEnergyConsumption("DEV-ENERGY-001");

        assertThat(result.error()).isNull();
        assertThat(result.reading()).isNotNull();
        assertThat(result.reading().currentKwh()).isEqualTo(138.0);
        assertThat(result.reading().varianceRatio()).isEqualTo(0.38);
        assertThat(result.notice()).contains("Mock");
    }

    @Test
    void unknownMeterReturnsSafeErrorWithoutInventingConsumption() {
        EnergyQueryTool.EnergyLookupResult result = new EnergyQueryTool(new MockParkFixture().energy())
                .lookupEnergyConsumption("missing-meter");

        assertThat(result.reading()).isNull();
        assertThat(result.error()).contains("Unknown energy meter");
    }
}
