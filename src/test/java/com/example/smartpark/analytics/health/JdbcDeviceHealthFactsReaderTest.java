package com.example.smartpark.analytics.health;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDeviceHealthFactsReaderTest {
    @Test
    void readsDeviceAndActiveAlertsThroughTheGuardedReadOnlySnapshot() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.executeInConsistentSnapshot(any())).thenAnswer(invocation ->
                ((ReadOnlyQueryExecutor.SnapshotWork<?>) invocation.getArgument(0)).call());
        when(executor.execute(any(), any())).thenReturn(
                new TabularResult(List.of("device_id", "building_id", "device_type", "status", "snapshot_at"),
                        List.of(List.of("AC-B1-07", "B1", "HVAC", "ONLINE",
                                Instant.parse("2026-09-08T09:00:00Z"))), false, 4),
                new TabularResult(List.of("alert_id", "building_id", "device_id", "category", "risk_level",
                        "status", "occurred_at"),
                        List.of(List.of("ALT-001", "B1", "AC-B1-07", "TEMPERATURE", "MEDIUM", "OPEN",
                                Instant.parse("2026-09-08T09:10:00Z"))), true, 5));

        var facts = new JdbcDeviceHealthFactsReader(cost, executor).read("AC-B1-07");

        assertThat(facts.available()).isTrue();
        assertThat(facts.truncated()).isTrue();
        assertThat(facts.device().buildingId()).isEqualTo("B1");
        assertThat(facts.activeAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.alertId()).isEqualTo("ALT-001");
            assertThat(alert.riskLevel()).isEqualTo("MEDIUM");
        });
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(cost, times(2)).estimatedCost(sql.capture(), parameters.capture());
        assertThat(sql.getAllValues()).allSatisfy(statement -> {
            assertThat(statement).contains(":deviceId").doesNotContain("AC-B1-07");
        });
        assertThat(parameters.getAllValues()).allSatisfy(values ->
                assertThat(values).containsEntry("deviceId", "AC-B1-07"));
        verify(executor, times(2)).execute(any(ValidatedSql.class), any());
    }

    @Test
    void resolvesAlertScopeWithAParameterizedGuardedQuery() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), any())).thenReturn(
                new TabularResult(List.of("device_id"), List.of(List.of("AC-B1-07")), false, 2));

        String deviceId = new JdbcDeviceHealthFactsReader(cost, executor).findDeviceIdByAlertId("ALT-001");

        assertThat(deviceId).isEqualTo("AC-B1-07");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(cost).estimatedCost(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains(":alertId", "LIMIT 1").doesNotContain("ALT-001");
        assertThat(parameters.getValue()).containsEntry("alertId", "ALT-001");
        verify(executor).execute(any(ValidatedSql.class), any());
    }

    @Test
    void redactsBackendFailures() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.executeInConsistentSnapshot(any()))
                .thenThrow(new IllegalStateException("jdbc://secret-host password=secret"));

        var facts = new JdbcDeviceHealthFactsReader(cost, executor).read("AC-B1-07");

        assertThat(facts.available()).isFalse();
        assertThat(facts.failureCode()).isEqualTo("DEVICE_HEALTH_FACTS_UNAVAILABLE")
                .doesNotContain("jdbc", "secret", "password");
    }
}
