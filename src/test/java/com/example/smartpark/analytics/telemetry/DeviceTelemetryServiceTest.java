package com.example.smartpark.analytics.telemetry;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceTelemetryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T10:30:00Z");
    private static final Instant FROM = Instant.parse("2026-09-08T06:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void returnsSortedMultiDeviceSeriesWithUnitTimezoneQualityAsOfAndThresholdSource() {
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(
                List.of(device("AC-B1-07", "B1"), device("HUM-B2-11", "B2")),
                List.of(row("HUM-B2-11", "B2", 8, "23.2"), row("AC-B1-07", "B1", 7, "28.5"),
                        row("AC-B1-07", "B1", 6, "27.0"), row("HUM-B2-11", "B2", 9, "23.4")), false));

        var response = service(reader).query(query("TEMPERATURE", List.of("ac-b1-07", "HUM-B2-11"), FROM, TO));

        assertThat(response.telemetryType()).isEqualTo("TEMPERATURE");
        assertThat(response.unit()).isEqualTo("°C");
        assertThat(response.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.status()).isEqualTo(DeviceTelemetryDtos.Status.PARTIAL);
        assertThat(response.series()).extracting(DeviceTelemetryDtos.Series::deviceId)
                .containsExactly("AC-B1-07", "HUM-B2-11");
        assertThat(response.series().get(0).points()).extracting(DeviceTelemetryDtos.Point::timestamp)
                .containsExactly(hour(6), hour(7));
        assertThat(response.series().get(0).points()).extracting(DeviceTelemetryDtos.Point::quality)
                .containsOnly("GOOD");
        assertThat(response.series().get(0).missingTimestamps()).containsExactly(hour(8), hour(9));
        assertThat(response.asOf()).isEqualTo(hour(9));
        assertThat(response.source().datasetKind()).isEqualTo("DEMO");
        assertThat(response.threshold().source()).isEqualTo("DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1");
    }

    @Test
    void reportsAvailableOnlyWhenEveryRequestedBucketExists() {
        List<DeviceTelemetryReader.Row> rows = IntStream.range(6, 10)
                .mapToObj(hour -> row("AC-B1-07", "B1", hour, "24.0")).toList();
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(
                List.of(device("AC-B1-07", "B1")), rows, false));

        var response = service(reader).query(query("TEMPERATURE", List.of("AC-B1-07"), FROM, TO));

        assertThat(response.status()).isEqualTo(DeviceTelemetryDtos.Status.AVAILABLE);
        assertThat(response.series()).singleElement().satisfies(series -> {
            assertThat(series.missingTimestamps()).isEmpty();
            assertThat(series.freshness()).isEqualTo(DeviceTelemetryDtos.Freshness.FRESH);
        });
    }

    @Test
    void returnsUnavailableWithoutFakeSeriesForNotReadyEmptyAndFailedSources() {
        FakeReader neverCalled = new FakeReader(DeviceTelemetryReader.Snapshot.unavailable("secret"));
        var vibration = service(neverCalled).query(query("VIBRATION", List.of("AC-B1-07"), FROM, TO));
        assertThat(vibration.status()).isEqualTo(DeviceTelemetryDtos.Status.UNAVAILABLE);
        assertThat(vibration.series()).isEmpty();
        assertThat(neverCalled.calls).isZero();

        for (DeviceTelemetryReader.Snapshot snapshot : List.of(
                DeviceTelemetryReader.Snapshot.available(List.of(device("AC-B1-07", "B1")), List.of(), false),
                DeviceTelemetryReader.Snapshot.unavailable("jdbc://secret/password"))) {
            var response = service(new FakeReader(snapshot)).query(query("TEMPERATURE", List.of("AC-B1-07"), FROM, TO));
            assertThat(response.status()).isEqualTo(DeviceTelemetryDtos.Status.UNAVAILABLE);
            assertThat(response.series()).isEmpty();
            assertThat(response.toString()).doesNotContain("secret", "jdbc", "password");
        }
    }

    @Test
    void distinguishesUnknownDeviceFromAValidDeviceWithNoData() {
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(List.of(), List.of(), false));
        assertThatThrownBy(() -> service(reader).query(query("TEMPERATURE", List.of("MISSING"), FROM, TO)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void marksOldObservedDataStale() {
        Instant oldFrom = Instant.parse("2026-09-07T06:00:00Z");
        Instant oldTo = Instant.parse("2026-09-07T10:00:00Z");
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(
                List.of(device("AC-B1-07", "B1")),
                List.of(new DeviceTelemetryReader.Row("AC-B1-07", "B1", "HVAC", oldFrom,
                        new BigDecimal("24"), "GOOD", oldFrom)), false));
        var response = service(reader).query(query("TEMPERATURE", List.of("AC-B1-07"), oldFrom, oldTo));
        assertThat(response.series()).singleElement()
                .extracting(DeviceTelemetryDtos.Series::freshness)
                .isEqualTo(DeviceTelemetryDtos.Freshness.STALE);
    }

    @Test
    void doesNotClassifyFutureDatedTelemetryAsFresh() {
        Instant futureBucket = hour(11);
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(
                List.of(device("AC-B1-07", "B1")),
                List.of(new DeviceTelemetryReader.Row("AC-B1-07", "B1", "HVAC", futureBucket,
                        new BigDecimal("24"), "GOOD", futureBucket)), false));

        var response = service(reader).query(query("TEMPERATURE", List.of("AC-B1-07"), FROM, hour(12)));

        assertThat(response.series()).singleElement()
                .extracting(DeviceTelemetryDtos.Series::freshness)
                .isEqualTo(DeviceTelemetryDtos.Freshness.UNKNOWN);
    }

    @Test
    void rejectsInvalidTypeDeviceWindowCountAndPointVolumeBeforeReading() {
        FakeReader reader = new FakeReader(DeviceTelemetryReader.Snapshot.available(List.of(), List.of(), false));
        DeviceTelemetryService service = service(reader);
        assertThatThrownBy(() -> service.query(query("whatever", List.of("AC-B1-07"), FROM, TO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", List.of("x' OR 1=1"), FROM, TO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", List.of("AC-B1-07"), TO, FROM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", List.of("AC-B1-07"),
                FROM, TO.plusSeconds(1)))).isInstanceOf(IllegalArgumentException.class);
        List<String> elevenDevices = IntStream.rangeClosed(1, 11).mapToObj(index -> "DEVICE-" + index).toList();
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", elevenDevices, FROM, TO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", List.of("AC-B1-07"),
                FROM, FROM.plus(8, java.time.temporal.ChronoUnit.DAYS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("TEMPERATURE", List.of("AC-B1-07", "HUM-B2-11", "AC-B3-03"),
                FROM, FROM.plus(7, java.time.temporal.ChronoUnit.DAYS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reader.calls).isZero();
    }

    private static DeviceTelemetryService service(FakeReader reader) {
        return new DeviceTelemetryService(new TelemetryCatalog(), reader,
                Clock.fixed(NOW, ZoneOffset.UTC), DeviceTelemetryService.FACT_TIMEZONE);
    }
    private static DeviceTelemetryQuery query(String type, List<String> ids, Instant from, Instant to) {
        return new DeviceTelemetryQuery(type, ids, from, to, DeviceTelemetryQuery.Granularity.HOUR);
    }
    private static DeviceTelemetryReader.DeviceDescriptor device(String id, String building) {
        return new DeviceTelemetryReader.DeviceDescriptor(id, building, "HVAC", "ONLINE", NOW.minusSeconds(3600));
    }
    private static DeviceTelemetryReader.Row row(String id, String building, int hour, String value) {
        Instant timestamp = hour(hour);
        return new DeviceTelemetryReader.Row(id, building, "HVAC", timestamp,
                new BigDecimal(value), "GOOD", timestamp);
    }
    private static Instant hour(int hour) { return Instant.parse("2026-09-08T" + String.format("%02d", hour) + ":00:00Z"); }

    private static final class FakeReader implements DeviceTelemetryReader {
        private final Snapshot snapshot;
        private int calls;
        private FakeReader(Snapshot snapshot) { this.snapshot = snapshot; }
        @Override public Snapshot read(Request request, TelemetryCatalog.Definition definition) {
            calls++;
            return snapshot;
        }
    }
}
