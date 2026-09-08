package com.example.smartpark.analytics.energy;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcEnergyTimeSeriesReaderTest {
    @Test
    void appliesAstCostAndReadOnlyExecutionToAParameterizedHourlyQuery() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), any())).thenReturn(new TabularResult(
                List.of("building_id", "bucket_ts", "metric_value", "observed_at"),
                List.of(List.of("B1", Instant.parse("2026-09-07T00:00:00Z"),
                        new BigDecimal("12.5"), Instant.parse("2026-09-07T00:00:00Z"))), false, 4));
        var reader = new JdbcEnergyTimeSeriesReader(cost, executor, ZoneId.of("Asia/Shanghai"));

        var snapshot = reader.read(new EnergyTimeSeriesReader.Request(List.of("B1"),
                        Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T01:00:00Z"),
                        EnergyTimeSeriesQuery.Granularity.HOUR),
                new MetricCatalog().findByName("energy_kwh").orElseThrow());

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.rows()).singleElement().satisfies(row -> {
            assertThat(row.buildingId()).isEqualTo("B1");
            assertThat(row.value()).isEqualByComparingTo("12.5");
        });
        ArgumentCaptor<String> costSql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> costParameters = ArgumentCaptor.forClass(Map.class);
        verify(cost).estimatedCost(costSql.capture(), costParameters.capture());
        assertThat(costSql.getValue()).contains("analytics.v_energy_hourly", "building_id IN (:buildingIds)", "LIMIT 500")
                .doesNotContain("B1");
        assertThat(costParameters.getValue()).containsEntry("buildingIds", List.of("B1"));
        ArgumentCaptor<ValidatedSql> validatedSql = ArgumentCaptor.forClass(ValidatedSql.class);
        verify(executor).execute(validatedSql.capture(), any());
        assertThat(validatedSql.getValue().namedParameters()).contains("from", "to", "buildingIds");
    }

    @Test
    void mapsDailyBucketsUsingTheConfiguredFactTimezone() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(executor.execute(any(), any())).thenReturn(new TabularResult(
                List.of("building_id", "bucket_date", "metric_value", "observed_at"),
                List.of(List.of("B1", Date.valueOf("2026-09-07"), new BigDecimal("3.5"),
                        Instant.parse("2026-09-07T15:00:00Z"))), false, 4));
        var reader = new JdbcEnergyTimeSeriesReader(cost, executor, ZoneId.of("Asia/Shanghai"));

        var snapshot = reader.read(new EnergyTimeSeriesReader.Request(List.of("B1"),
                        Instant.parse("2026-09-06T16:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"),
                        EnergyTimeSeriesQuery.Granularity.DAY),
                new MetricCatalog().findByName("energy_deviation_pct").orElseThrow());

        assertThat(snapshot.rows()).singleElement()
                .extracting(EnergyTimeSeriesReader.Row::bucketTimestamp)
                .isEqualTo(Instant.parse("2026-09-06T16:00:00Z"));
    }

    @Test
    void convertsInternalFailuresToAnUnavailableSnapshotWithoutLeakingDetails() throws Exception {
        QueryCostGuard cost = mock(QueryCostGuard.class);
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        when(cost.estimatedCost(any(), any())).thenThrow(new IllegalStateException("jdbc://secret-host/password"));
        var reader = new JdbcEnergyTimeSeriesReader(cost, executor, ZoneId.of("Asia/Shanghai"));

        var snapshot = reader.read(new EnergyTimeSeriesReader.Request(List.of("B1"),
                        Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T01:00:00Z"),
                        EnergyTimeSeriesQuery.Granularity.HOUR),
                new MetricCatalog().findByName("energy_kwh").orElseThrow());

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.rows()).isEmpty();
        assertThat(snapshot.failureCode()).isEqualTo("ENERGY_QUERY_UNAVAILABLE")
                .doesNotContain("secret-host", "password", "jdbc");
    }
}
