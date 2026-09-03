package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.execution.LegacyWorkflowEventAdapter;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcAnalyticsReaderTest {
    @Test
    void alertReaderMapsGroupedFactsAndKeepsQueryOnAlertView() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
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
        consistentSnapshot(executor);
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

    @Test
    void readersPassDeclaredSqlLimitsToTheExecutor() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            ValidatedSql sql = invocation.getArgument(0, ValidatedSql.class);
            if (sql.sql().contains("v_alert_fact") && sql.sql().contains("alert_id")) {
                assertThat(sql.maxRows()).isEqualTo(10);
            } else if (sql.sql().contains("v_device_snapshot") && sql.sql().contains("device_id")) {
                assertThat(sql.maxRows()).isEqualTo(20);
            } else if (sql.sql().contains("v_energy_hourly") && sql.sql().contains("meter_id")) {
                assertThat(sql.maxRows()).isEqualTo(10);
            } else if (sql.sql().contains("LIMIT")) {
                assertThat(sql.maxRows()).isEqualTo(50);
            } else {
                assertThat(sql.maxRows()).isEqualTo(500);
            }
            return rows(List.of("key", "count"), List.of());
        });

        new JdbcAlertAnalyticsReader(executor).read(query());
        new JdbcAlertAnalyticsReader(executor).evidence("B1", query());
        new JdbcDeviceAnalyticsReader(executor).read(query());
        new JdbcDeviceAnalyticsReader(executor).evidence("B1", query());
        new JdbcEnergyAnalyticsReader(executor).read(query());
        new JdbcEnergyAnalyticsReader(executor).evidence("B1", query());
    }

    @Test
    void deviceReaderBindsOnlyTheRecentOneDayForEveryQuery() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
        Instant expectedFrom = Instant.parse("2026-09-02T00:00:00Z");
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> parameters = invocation.getArgument(1, Map.class);
            assertThat(parameters.get("from")).isEqualTo(expectedFrom);
            return rows(List.of("key", "count"), List.of());
        });

        new JdbcDeviceAnalyticsReader(executor).read(query());
        new JdbcDeviceAnalyticsReader(executor).evidence("B1", query());
    }

    @Test
    void deviceEvidenceContainsOnlyOfflineSnapshots() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            ValidatedSql sql = invocation.getArgument(0, ValidatedSql.class);
            if (sql.sql().contains("device_id")) {
                assertThat(sql.sql()).contains("status = 'OFFLINE'");
                assertThat(sql.sql()).doesNotContain("status <> 'ONLINE'");
            }
            return rows(List.of("device_id", "building_id", "device_type", "status", "snapshot_at", "open_alert_count"), List.of());
        });

        new JdbcDeviceAnalyticsReader(executor).evidence("B1", query());
    }

    @Test
    void overviewReadersExecuteAllAggregatesInsideOneConsistentSnapshot() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
        when(executor.execute(any(), anyMap())).thenReturn(rows(List.of("key", "count"), List.of()));

        new JdbcAlertAnalyticsReader(executor).read(query());
        new JdbcDeviceAnalyticsReader(executor).read(query());

        verify(executor, times(2)).executeInConsistentSnapshot(any());
    }

    @Test
    void alertEvidenceIncludesTheExistingWorkflowRunId() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        WorkflowExecutionStore workflowStore = mock(WorkflowExecutionStore.class);
        WorkflowSnapshot snapshot = mock(WorkflowSnapshot.class);
        when(snapshot.workflowId()).thenReturn("run-1");
        when(workflowStore.findByAlertId("ALT-1")).thenReturn(Optional.of(snapshot));
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (sql.contains("alert_id")) {
                return rows(List.of("alert_id", "building_id", "device_id", "category", "risk_level", "status", "occurred_at"),
                        List.of(List.of("ALT-1", "B1", "DEV-1", "POWER", "HIGH", "OPEN", Instant.parse("2026-09-03T01:00:00Z"))));
            }
            return rows(List.of("key", "count"), List.of());
        });

        var result = new JdbcAlertAnalyticsReader(executor, workflowStore).evidence("B1", query());

        assertThat(result.items()).singleElement().extracting(AlertAnalyticsReader.AlertReference::executionRunId)
                .isEqualTo(LegacyWorkflowEventAdapter.runIdFor("run-1").toString());
        verify(workflowStore).findByAlertId("ALT-1");
    }

    @Test
    void alertReaderPropagatesTruncationFromGroupedQueries() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (sql.contains("COUNT(*) AS alert_count")) {
                return rows(List.of("alert_count", "high_risk_alert_count"), List.of(List.of(2L, 1L)));
            }
            return truncatedRows(List.of("key", "count"));
        });

        AlertAnalyticsReader.Snapshot snapshot = new JdbcAlertAnalyticsReader(executor).read(query());

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.failureCode()).isEqualTo("RESULT_TRUNCATED");
    }

    @Test
    void deviceReaderPropagatesTruncationFromGroupedQueries() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        consistentSnapshot(executor);
        when(executor.execute(any(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, ValidatedSql.class).sql();
            if (sql.contains("MAX(snapshot_at)")) {
                return rows(List.of("offline_device_count", "as_of"), List.of(List.of(1L, Instant.parse("2026-09-03T02:00:00Z"))));
            }
            return truncatedRows(List.of("key", "count"));
        });

        DeviceAnalyticsReader.Snapshot snapshot = new JdbcDeviceAnalyticsReader(executor).read(query());

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.failureCode()).isEqualTo("RESULT_TRUNCATED");
    }

    private static OperationsAnomalyQuery query() {
        return new OperationsAnomalyQuery(Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-09-03T00:00:00Z"), null, null, null, null, null);
    }

    private static TabularResult rows(List<String> columns, List<List<Object>> values) {
        return new TabularResult(columns, values, false, 1);
    }

    private static TabularResult truncatedRows(List<String> columns) {
        return new TabularResult(columns, List.of(), true, 50);
    }

    private static void consistentSnapshot(ReadOnlyQueryExecutor executor) throws Exception {
        when(executor.executeInConsistentSnapshot(any())).thenAnswer(invocation ->
                invocation.getArgument(0, ReadOnlyQueryExecutor.SnapshotWork.class).call());
    }
}
