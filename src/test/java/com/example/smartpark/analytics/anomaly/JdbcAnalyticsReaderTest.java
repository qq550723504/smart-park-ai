package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcAnalyticsReaderTest {
    @Test
    void alertReaderMapsGroupedFactsAndKeepsQueryOnAlertView() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (!sql.contains("analytics.v_alert_fact")) throw new AssertionError("alert reader crossed view boundary");
            if (sql.contains("GROUP BY building_id")) return rows(List.of("building_id", "alert_count", "high_risk_alert_count"), List.of(List.of("B1", 2L, 1L)));
            if (sql.contains("COUNT(*) AS alert_count")) return rows(List.of("alert_count", "high_risk_alert_count"), List.of(List.of(2L, 1L)));
            if (sql.contains("GROUP BY risk_level")) return rows(List.of("key", "count"), List.of(List.of("HIGH", 1L), List.of("LOW", 1L)));
            if (sql.contains("GROUP BY category")) return rows(List.of("key", "count"), List.of(List.of("POWER", 1L), List.of("TEMPERATURE", 1L)));
            if (sql.contains("GROUP BY status")) return rows(List.of("key", "count"), List.of(List.of("OPEN", 1L), List.of("RESOLVED", 1L)));
            return rows(List.of("alert_id", "building_id", "device_id", "category", "risk_level", "status", "occurred_at"), List.of(List.of("ALT-1", "B1", "DEV-1", "TEMPERATURE", "HIGH", "OPEN", Instant.parse("2026-09-03T01:00:00Z"))));
        });

        AlertAnalyticsReader.Snapshot snapshot = new JdbcAlertAnalyticsReader(executor).read(query());

        assertThat(snapshot.alertCount()).isEqualTo(2);
        assertThat(snapshot.highRiskAlertCount()).isEqualTo(1);
        assertThat(snapshot.buildings()).extracting(AlertAnalyticsReader.BuildingSummary::buildingId).containsExactly("B1");
    }

    @Test
    void deviceReaderMapsOfflineSnapshotAndAsOf() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (!sql.contains("analytics.v_device_snapshot")) throw new AssertionError("device reader crossed view boundary");
            if (sql.contains("MAX(snapshot_at)")) return rows(List.of("offline_device_count", "as_of"), List.of(List.of(1L, Instant.parse("2026-09-03T02:00:00Z"))));
            if (sql.contains("GROUP BY device_type")) return rows(List.of("key", "count"), List.of(List.of("HVAC", 1L)));
            return rows(List.of("building_id", "offline_device_count"), List.of(List.of("B1", 1L)));
        });

        DeviceAnalyticsReader.Snapshot snapshot = new JdbcDeviceAnalyticsReader(executor).read(query());

        assertThat(snapshot.offlineDeviceCount()).isEqualTo(1);
        assertThat(snapshot.asOf()).isEqualTo(Instant.parse("2026-09-03T02:00:00Z"));
        assertThat(snapshot.deviceTypes()).extracting(AlertAnalyticsReader.Breakdown::key).containsExactly("HVAC");
    }

    @Test
    void energyReaderMapsDeviationWithoutCrossViewJoin() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (!sql.contains("analytics.v_energy_hourly") || sql.contains("v_alert_fact") || sql.contains("v_device_snapshot")) throw new AssertionError("energy reader crossed view boundary");
            return rows(List.of("building_id", "kwh", "baseline_kwh", "measured_at"), List.of(List.of("B1", 120.0, 100.0, Instant.parse("2026-09-03T03:00:00Z"))));
        });

        EnergyAnalyticsReader.Snapshot snapshot = new JdbcEnergyAnalyticsReader(executor).read(query());

        assertThat(snapshot.buildings()).singleElement().satisfies(building -> {
            assertThat(building.buildingId()).isEqualTo("B1");
            assertThat(building.deviationPct()).isEqualTo(20.0);
        });
    }

    private static OperationsAnomalyQuery query() {
        return new OperationsAnomalyQuery(Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z"), null, null, null, null, null);
    }

    private static TabularResult rows(List<String> columns, List<List<Object>> values) {
        return new TabularResult(columns, values, false, 1);
    }
}
