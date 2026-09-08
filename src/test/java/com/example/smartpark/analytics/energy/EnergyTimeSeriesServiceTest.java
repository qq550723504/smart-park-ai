package com.example.smartpark.analytics.energy;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyTimeSeriesServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant H0 = Instant.parse("2026-09-07T00:00:00Z");
    private static final Instant H1 = Instant.parse("2026-09-07T01:00:00Z");
    private static final Instant H2 = Instant.parse("2026-09-07T02:00:00Z");
    private static final Instant H3 = Instant.parse("2026-09-07T03:00:00Z");

    @Test
    void returnsSortedSingleBuildingSeriesWithCatalogUnitTimezoneAsOfAndEvidence() {
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.available(List.of(
                row("B1", H2, "30"), row("B1", H0, "10"), row("B1", H1, "20")), false));

        var response = service(reader).query(query("energy_kwh", List.of("b1"), H0, H3,
                EnergyTimeSeriesQuery.Granularity.HOUR));

        assertThat(response.metric()).isEqualTo("energy_kwh");
        assertThat(response.unit()).isEqualTo("kWh");
        assertThat(response.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.AVAILABLE);
        assertThat(response.series()).singleElement().satisfies(series -> {
            assertThat(series.buildingId()).isEqualTo("B1");
            assertThat(series.points()).extracting(EnergyTimeSeriesDtos.Point::timestamp)
                    .containsExactly(H0, H1, H2);
            assertThat(series.points()).extracting(EnergyTimeSeriesDtos.Point::value)
                    .containsExactly(new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));
            assertThat(series.missingTimestamps()).isEmpty();
        });
        assertThat(response.asOf()).isEqualTo(H2);
        assertThat(response.source().system()).isEqualTo("OPERATIONS_ANALYTICS");
        assertThat(response.evidence()).singleElement().satisfies(item -> {
            assertThat(item.expectedPointCount()).isEqualTo(3);
            assertThat(item.actualPointCount()).isEqualTo(3);
        });
        assertThat(reader.metric.name()).isEqualTo("energy_kwh");
    }

    @Test
    void preservesIndependentBuildingSeriesAndReportsMissingBucketsAsPartialWithoutZeroFill() {
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.available(List.of(
                row("B2", H0, "40"), row("B1", H1, "20"), row("B1", H0, "10")), false));

        var response = service(reader).query(query("energy_kwh", List.of("B1", "B2"), H0, H2,
                EnergyTimeSeriesQuery.Granularity.HOUR));

        assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.PARTIAL);
        assertThat(response.series()).extracting(EnergyTimeSeriesDtos.Series::buildingId)
                .containsExactly("B1", "B2");
        assertThat(response.series().get(0).missingTimestamps()).isEmpty();
        assertThat(response.series().get(1).points()).singleElement()
                .extracting(EnergyTimeSeriesDtos.Point::value).isEqualTo(new BigDecimal("40"));
        assertThat(response.series().get(1).missingTimestamps()).containsExactly(H1);
        assertThat(response.series().get(1).points()).noneMatch(point -> point.value().signum() == 0);
    }

    @Test
    void exposesDailyBaselineDeviationFromTheRegisteredMetric() {
        Instant day0 = Instant.parse("2026-09-05T16:00:00Z");
        Instant day1 = Instant.parse("2026-09-06T16:00:00Z");
        Instant day2 = Instant.parse("2026-09-07T16:00:00Z");
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.available(List.of(
                row("B1", day0, "4.25"), row("B1", day1, "-1.50")), false));

        var response = service(reader).query(query("energy_deviation_pct", List.of("B1"), day0, day2,
                EnergyTimeSeriesQuery.Granularity.DAY));

        assertThat(response.metric()).isEqualTo("energy_deviation_pct");
        assertThat(response.unit()).isEqualTo("%");
        assertThat(response.window().granularity()).isEqualTo(EnergyTimeSeriesQuery.Granularity.DAY);
        assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.AVAILABLE);
        assertThat(reader.request.granularity()).isEqualTo(EnergyTimeSeriesQuery.Granularity.DAY);
    }

    @Test
    void returnsUnavailableWithNoFakeSeriesForEmptyOrFailedSources() {
        for (EnergyTimeSeriesReader.Snapshot snapshot : List.of(
                EnergyTimeSeriesReader.Snapshot.available(List.of(), false),
                EnergyTimeSeriesReader.Snapshot.unavailable("jdbc://secret-host/internal"))) {
            var response = service(new FakeReader(snapshot)).query(query("energy_kwh", List.of("B1"), H0, H2,
                    EnergyTimeSeriesQuery.Granularity.HOUR));

            assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.UNAVAILABLE);
            assertThat(response.series()).isEmpty();
            assertThat(response.asOf()).isNull();
            assertThat(response.toString()).doesNotContain("secret-host", "jdbc:");
        }
    }

    @Test
    void marksBoundedExecutorTruncationAsPartial() {
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.available(List.of(
                row("B1", H0, "10"), row("B1", H1, "20")), true));

        var response = service(reader).query(query("energy_kwh", List.of("B1"), H0, H2,
                EnergyTimeSeriesQuery.Granularity.HOUR));

        assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.PARTIAL);
    }

    @Test
    void defaultsToFiveCompletedHoursDaysAndNeverIncludesAnIncompleteHour() {
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.unavailable("OFFLINE"));

        service(reader).query(new EnergyTimeSeriesQuery(null, List.of("B1"), null, null, null));

        assertThat(reader.request.to()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
        assertThat(reader.request.from()).isEqualTo(Instant.parse("2026-09-03T10:00:00Z"));
    }

    @Test
    void rejectsInvalidRangesBuildingsMetricsAndGranularityAlignmentBeforeReading() {
        FakeReader reader = new FakeReader(EnergyTimeSeriesReader.Snapshot.available(List.of(), false));
        EnergyTimeSeriesService service = service(reader);

        assertThatThrownBy(() -> service.query(query("energy_kwh", List.of("B1"), H2, H1,
                EnergyTimeSeriesQuery.Granularity.HOUR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("energy_kwh", List.of("B1"), H0,
                H0.plusSeconds(60), EnergyTimeSeriesQuery.Granularity.HOUR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("energy_kwh", List.of("B1 OR 1=1"), H0, H2,
                EnergyTimeSeriesQuery.Granularity.HOUR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("peak_kw", List.of("B1"), H0, H2,
                EnergyTimeSeriesQuery.Granularity.HOUR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("energy_kwh", List.of("B1", "B2", "B3"), H0,
                H0.plus(7, java.time.temporal.ChronoUnit.DAYS), EnergyTimeSeriesQuery.Granularity.HOUR)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reader.calls).isZero();
    }

    private static EnergyTimeSeriesService service(FakeReader reader) {
        return new EnergyTimeSeriesService(new MetricCatalog(), reader,
                Clock.fixed(Instant.parse("2026-09-08T10:37:00Z"), ZoneOffset.UTC), ZONE);
    }

    private static EnergyTimeSeriesQuery query(String metric, List<String> ids, Instant from, Instant to,
                                               EnergyTimeSeriesQuery.Granularity granularity) {
        return new EnergyTimeSeriesQuery(metric, ids, from, to, granularity);
    }

    private static EnergyTimeSeriesReader.Row row(String buildingId, Instant timestamp, String value) {
        return new EnergyTimeSeriesReader.Row(buildingId, timestamp, new BigDecimal(value), timestamp);
    }

    private static final class FakeReader implements EnergyTimeSeriesReader {
        private final Snapshot snapshot;
        private Request request;
        private MetricDefinition metric;
        private int calls;

        private FakeReader(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Snapshot read(Request request, MetricDefinition metric) {
            calls++;
            this.request = request;
            this.metric = metric;
            return snapshot;
        }
    }
}
