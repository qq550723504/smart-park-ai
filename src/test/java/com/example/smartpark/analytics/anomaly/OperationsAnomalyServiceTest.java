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

    @Test
    void ignoresEnergyRowsWithoutANonZeroDeviationSignal() {
        EnergyAnalyticsReader energy = query -> new EnergyAnalyticsReader.Snapshot(List.of(
                new EnergyAnalyticsReader.BuildingSummary("B1", null, 100.0, null, NOW),
                new EnergyAnalyticsReader.BuildingSummary("B2", 0.0, 100.0, 100.0, NOW),
                new EnergyAnalyticsReader.BuildingSummary("B3", 12.5, 112.5, 100.0, NOW)), true, null);

        OperationsAnomalyDtos.Overview overview = new OperationsAnomalyService(
                query -> new AlertAnalyticsReader.Snapshot(0, 0, List.of(), List.of(), List.of(), List.of(), true, null),
                query -> new DeviceAnalyticsReader.Snapshot(0, List.of(), List.of(), NOW, true, null),
                energy, Clock.fixed(NOW, ZoneOffset.UTC)).overview(query());

        assertThat(overview.summary().affectedBuildingCount()).isEqualTo(1);
        assertThat(overview.buildings()).extracting(OperationsAnomalyDtos.BuildingSummary::buildingId)
                .containsExactly("B3");
    }

    @Test
    void preservesMissingDeviceAsOfInsteadOfInventingTheWindowEnd() {
        OperationsAnomalyDtos.Overview overview = new OperationsAnomalyService(
                query -> new AlertAnalyticsReader.Snapshot(1, 0, List.of(), List.of(), List.of(),
                        List.of(new AlertAnalyticsReader.BuildingSummary("B1", 1, 0)), true, null),
                query -> DeviceAnalyticsReader.Snapshot.unavailable("QUERY_TIMEOUT"),
                query -> EnergyAnalyticsReader.Snapshot.unavailable("QUERY_TIMEOUT"),
                Clock.fixed(NOW, ZoneOffset.UTC)).overview(query());

        assertThat(overview.asOf()).isNull();
    }

    @Test
    void marksEvidenceDomainUnavailableWhenItsEvidenceReadFails() {
        AlertAnalyticsReader alerts = new AlertAnalyticsReader() {
            @Override
            public Snapshot read(OperationsAnomalyQuery query) {
                return new Snapshot(1, 0, List.of(), List.of(), List.of(),
                        List.of(new BuildingSummary("B1", 1, 0)), true, null);
            }

            @Override
            public EvidenceResult<AlertReference> evidence(String buildingId, OperationsAnomalyQuery query) {
                return EvidenceResult.unavailable("QUERY_TIMEOUT");
            }
        };
        OperationsAnomalyDtos.Evidence evidence = new OperationsAnomalyService(
                alerts,
                query -> new DeviceAnalyticsReader.Snapshot(0, List.of(), List.of(), NOW, true, null),
                query -> new EnergyAnalyticsReader.Snapshot(List.of(), true, null),
                Clock.fixed(NOW, ZoneOffset.UTC)).evidence("B1", query());

        assertThat(evidence.domainStatus().get("alerts")).isEqualTo("UNAVAILABLE");
    }

    private static OperationsAnomalyQuery query() {
        return new OperationsAnomalyQuery(null, null, null, null, null, null, null);
    }
}
