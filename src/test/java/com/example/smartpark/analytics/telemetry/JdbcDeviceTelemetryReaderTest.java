package com.example.smartpark.analytics.telemetry;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDeviceTelemetryReaderTest {
    @Test
    void appliesAstCostParameterBindingAndReadOnlyExecutionToBothQueries() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.executeInConsistentSnapshot(any())).thenAnswer(invocation ->
                ((ReadOnlyQueryExecutor.SnapshotWork<?>) invocation.getArgument(0)).call());
        when(executor.execute(any(), any())).thenReturn(
                new TabularResult(List.of("device_id", "building_id", "device_type", "status", "snapshot_at"),
                        List.of(List.of("AC-B1-07", "B1", "HVAC", "ONLINE",
                                Instant.parse("2026-09-08T09:00:00Z"))), false, 5),
                new TabularResult(List.of("device_id", "building_id", "device_type", "bucket_ts",
                        "value", "quality", "observed_at"),
                        List.of(List.of("AC-B1-07", "B1", "HVAC",
                                Instant.parse("2026-09-08T09:00:00Z"), new BigDecimal("24.5"), "GOOD",
                                Instant.parse("2026-09-08T09:00:00Z"))), false, 7));
        JdbcDeviceTelemetryReader reader = new JdbcDeviceTelemetryReader(cost, executor);

        var snapshot = reader.read(new DeviceTelemetryReader.Request(List.of("AC-B1-07"),
                        Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T10:00:00Z"),
                        DeviceTelemetryQuery.Granularity.HOUR),
                new TelemetryCatalog().resolve("TEMPERATURE"));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.devices()).singleElement().extracting(DeviceTelemetryReader.DeviceDescriptor::buildingId)
                .isEqualTo("B1");
        assertThat(snapshot.rows()).singleElement().satisfies(row -> {
            assertThat(row.value()).isEqualByComparingTo("24.5");
            assertThat(row.quality()).isEqualTo("GOOD");
        });
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(cost, times(2)).estimatedCost(sql.capture(), parameters.capture());
        assertThat(sql.getAllValues().get(1)).contains("analytics.v_device_telemetry_hourly",
                "telemetry_type = :telemetryType", "device_id IN (:deviceIds)", "LIMIT 500")
                .doesNotContain("AC-B1-07");
        assertThat(parameters.getAllValues().get(1)).containsEntry("telemetryType", "TEMPERATURE")
                .containsEntry("deviceIds", List.of("AC-B1-07"));
        ArgumentCaptor<ValidatedSql> validated = ArgumentCaptor.forClass(ValidatedSql.class);
        verify(executor, times(2)).execute(validated.capture(), any());
        assertThat(validated.getAllValues().get(1).namedParameters())
                .contains("telemetryType", "from", "to", "deviceIds");
    }

    @Test
    void redactsBackendFailureIntoAStableUnavailableCode() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.executeInConsistentSnapshot(any()))
                .thenThrow(new IllegalStateException("jdbc://secret-host password=secret"));

        var snapshot = new JdbcDeviceTelemetryReader(cost, executor).read(
                new DeviceTelemetryReader.Request(List.of("AC-B1-07"), Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(3600), DeviceTelemetryQuery.Granularity.HOUR),
                new TelemetryCatalog().resolve("TEMPERATURE"));

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.failureCode()).isEqualTo("TELEMETRY_QUERY_UNAVAILABLE")
                .doesNotContain("jdbc", "secret", "password");
    }
}
