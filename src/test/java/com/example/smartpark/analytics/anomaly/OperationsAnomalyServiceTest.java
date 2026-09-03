package com.example.smartpark.analytics.anomaly;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsAnomalyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void mergesDomainSignalsByBuildingAndKeepsUnavailableEnergyOutOfTotals() {
        AlertAnalyticsReader alerts = query -> new AlertAnalyticsReader.Snapshot(
                3, 1,
                List.of(new AlertAnalyticsReader.Breakdown("HIGH", 1)),
                List.of(new AlertAnalyticsReader.Breakdown("POWER", 3)),
                List.of(new AlertAnalyticsReader.Breakdown("OPEN", 2)),
                List.of(new AlertAnalyticsReader.BuildingSummary("B1", 2, 1), new AlertAnalyticsReader.BuildingSummary("B2", 1, 0)),
                true, null);
        DeviceAnalyticsReader devices = query -> new DeviceAnalyticsReader.Snapshot(
                1, List.of(new AlertAnalyticsReader.Breakdown("HVAC", 1)),
                List.of(new DeviceAnalyticsReader.BuildingSummary("B2", 1)), NOW, true, null);
        EnergyAnalyticsReader energy = query -> EnergyAnalyticsReader.Snapshot.unavailable("QUERY_TIMEOUT");

        OperationsAnomalyDtos.Overview overview = new OperationsAnomalyService(alerts, devices, energy,
                Clock.fixed(NOW, ZoneOffset.UTC)).overview(query());

        assertThat(overview.summary().alertCount()).isEqualTo(3);
        assertThat(overview.summary().offlineDeviceCount()).isEqualTo(1);
        assertThat(overview.summary().affectedBuildingCount()).isEqualTo(2);
        assertThat(overview.domainStatus().get("energy")).isEqualTo("UNAVAILABLE");
        assertThat(overview.buildings()).extracting(OperationsAnomalyDtos.BuildingSummary::buildingId)
                .containsExactly("B1", "B2");
        assertThat(overview.buildings().get(1).offlineDeviceCount()).isEqualTo(1);
        assertThat(overview.buildings().get(1).energyDeviationPct()).isNull();
    }

    private static OperationsAnomalyQuery query() {
        return new OperationsAnomalyQuery(null, null, null, null, null, null, null);
    }
}
