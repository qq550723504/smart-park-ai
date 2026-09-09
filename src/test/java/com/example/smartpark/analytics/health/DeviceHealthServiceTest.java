package com.example.smartpark.analytics.health;

import com.example.smartpark.analytics.telemetry.DeviceTelemetryDtos;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryService;
import com.example.smartpark.analytics.telemetry.TelemetryCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceHealthServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T10:30:00Z");
    private static final Instant FROM = Instant.parse("2026-09-07T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void returnsHealthyOnlyWithFreshConnectivityAndFreshTelemetryEvidence() {
        var response = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(9, "24")))).assess("AC-B1-07");

        assertThat(response.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.HEALTHY);
        assertThat(response.availability()).isEqualTo(DeviceHealthDtos.Availability.AVAILABLE);
        assertThat(response.reasons()).containsExactly("设备在线，且已登记遥测信号未触发阈值证据");
        assertThat(response.sources()).extracting(DeviceHealthDtos.Source::system)
                .contains("DEVICE_SNAPSHOT", "ALERT_FACT", "OPERATIONS_ANALYTICS_DEMO");
    }

    @Test
    void mapsLowMediumAndHighAlertsWithoutMixingOtherDevices() {
        assertThat(service(facts(device("ONLINE", "HVAC"), List.of(alert("LOW"))), unavailableTelemetry())
                .assess("AC-B1-07").healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.ATTENTION);
        assertThat(service(facts(device("ONLINE", "HVAC"), List.of(alert("MEDIUM"))), unavailableTelemetry())
                .assess("AC-B1-07").healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.DEGRADED);
        var critical = service(facts(device("ONLINE", "HVAC"), List.of(alert("HIGH"))), unavailableTelemetry())
                .assess("AC-B1-07");
        assertThat(critical.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.CRITICAL);
        assertThat(critical.evidence()).anyMatch(item -> item.reference().equals("alert:ALT-1"));

        var wrongBuilding = new DeviceHealthFactsReader.AlertFact("ALT-X", "B2", "AC-B1-07",
                "POWER", "HIGH", "OPEN", NOW.minusSeconds(900));
        assertThatThrownBy(() -> service(facts(device("ONLINE", "HVAC"), List.of(wrongBuilding)), unavailableTelemetry())
                .assess("AC-B1-07")).isInstanceOf(DeviceHealthService.DeviceHealthUnavailableException.class);
    }

    @Test
    void offlineConnectivityIsCriticalEvenWhenTelemetryIsMissing() {
        var response = service(facts(device("OFFLINE", "HVAC"), List.of()), unavailableTelemetry())
                .assess("AC-B1-07");
        assertThat(response.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.CRITICAL);
        assertThat(response.reasons()).contains("设备当前离线", "没有可用于当前健康判断的设备遥测");
    }

    @Test
    void usesOnlyRegisteredThresholdsForAttentionDegradedAndCritical() {
        var attention = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(8, "27"), point(9, "29")))).assess("AC-B1-07");
        assertThat(attention.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.ATTENTION);

        var degraded = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(7, "29"), point(8, "30"), point(9, "31"))))
                .assess("AC-B1-07");
        assertThat(degraded.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.DEGRADED);
        assertThat(degraded.evidence()).filteredOn(item -> item.type().equals("TELEMETRY_THRESHOLD"))
                .hasSize(3).allMatch(item -> item.summary().contains("DEMO_POLICY"));

        var critical = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(9, "36")))).assess("AC-B1-07");
        assertThat(critical.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.CRITICAL);
    }

    @Test
    void partialTelemetryCannotProduceHealthyAndGappedPointsAreNotSustained() {
        var partialBelowThreshold = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.PARTIAL, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(9, "24")))).assess("AC-B1-07");
        assertThat(partialBelowThreshold.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(partialBelowThreshold.reasons()).contains("设备遥测窗口不完整，不能据此确认健康");

        var gappedHighPoints = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.PARTIAL, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(6, "29"), point(8, "30"), point(9, "31"))))
                .assess("AC-B1-07");
        assertThat(gappedHighPoints.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.ATTENTION);
        assertThat(gappedHighPoints.reasons()).doesNotContain(
                "最近 3 个温度点持续超过已登记的 demo attention 阈值");
        assertThat(gappedHighPoints.evidence()).filteredOn(item -> item.type().equals("TELEMETRY_THRESHOLD"))
                .hasSize(1);
    }

    @Test
    void staleMissingOrUnregisteredTelemetryProducesUnknownRatherThanHealthy() {
        var stale = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.PARTIAL, DeviceTelemetryDtos.Freshness.STALE,
                        List.of(point(1, "24")))).assess("AC-B1-07");
        assertThat(stale.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(stale.availability()).isEqualTo(DeviceHealthDtos.Availability.PARTIAL);
        assertThat(stale.reasons()).contains("设备遥测已过期，未用于阈值判断");

        var incomplete = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.PARTIAL, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(9, "24")))).assess("AC-B1-07");
        assertThat(incomplete.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(incomplete.reasons()).contains("设备遥测窗口不完整，不能据此确认健康");

        var missing = service(facts(device("ONLINE", "HVAC"), List.of()), unavailableTelemetry())
                .assess("AC-B1-07");
        assertThat(missing.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);

        var noRegisteredThreshold = service(facts(device("ONLINE", "POWER_METER"), List.of()), unavailableTelemetry())
                .assess("AC-B1-07");
        assertThat(noRegisteredThreshold.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(noRegisteredThreshold.reasons()).contains("该设备类型没有已接入的遥测能力");

        var missingThreshold = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetryWithoutThreshold(List.of(point(9, "24")))).assess("AC-B1-07");
        assertThat(missingThreshold.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(missingThreshold.reasons()).contains("遥测可用但没有已登记阈值，未自动推导异常");
    }

    @Test
    void doesNotTreatThresholdBreachesAcrossAMissingHourAsSustained() {
        var response = service(facts(device("ONLINE", "HVAC"), List.of()),
                telemetry(DeviceTelemetryDtos.Status.PARTIAL, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(6, "29"), point(8, "30"), point(9, "31"))))
                .assess("AC-B1-07");

        assertThat(response.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.ATTENTION);
        assertThat(response.evidence()).filteredOn(item -> item.type().equals("TELEMETRY_THRESHOLD"))
                .singleElement().satisfies(item -> assertThat(item.summary()).contains("ATTENTION"));
    }

    @Test
    void futureDatedConnectivitySnapshotDoesNotProduceHealthy() {
        var futureDevice = new DeviceHealthFactsReader.DeviceFact("AC-B1-07", "B1", "HVAC", "ONLINE",
                NOW.plusSeconds(3600));
        var response = service(facts(futureDevice, List.of()),
                telemetry(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                        List.of(point(9, "24")))).assess("AC-B1-07");

        assertThat(response.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(response.reasons()).contains("设备状态快照已过期，不能据此判断当前健康");
    }

    @Test
    void failsClosedWhenFactsAreUnavailableAndDoesNotLeakReaderDetails() {
        var response = service(DeviceHealthFactsReader.Facts.unavailable("jdbc://secret/password"),
                unavailableTelemetry()).assess("AC-B1-07");
        assertThat(response.healthStatus()).isEqualTo(DeviceHealthDtos.HealthStatus.UNKNOWN);
        assertThat(response.availability()).isEqualTo(DeviceHealthDtos.Availability.UNAVAILABLE);
        assertThat(response.toString()).doesNotContain("jdbc", "secret", "password");
    }

    private static DeviceHealthService service(DeviceHealthFactsReader.Facts facts,
                                               DeviceTelemetryDtos.Response telemetryResponse) {
        DeviceTelemetryService telemetry = mock(DeviceTelemetryService.class);
        when(telemetry.query(any())).thenReturn(telemetryResponse);
        return new DeviceHealthService(ignored -> facts, telemetry, new TelemetryCatalog(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static DeviceHealthFactsReader.Facts facts(DeviceHealthFactsReader.DeviceFact device,
                                                       List<DeviceHealthFactsReader.AlertFact> alerts) {
        return DeviceHealthFactsReader.Facts.available(device, alerts);
    }
    private static DeviceHealthFactsReader.DeviceFact device(String status, String type) {
        return new DeviceHealthFactsReader.DeviceFact("AC-B1-07", "B1", type, status, NOW.minusSeconds(3600));
    }
    private static DeviceHealthFactsReader.AlertFact alert(String risk) {
        return new DeviceHealthFactsReader.AlertFact("ALT-1", "B1", "AC-B1-07", "TEMPERATURE",
                risk, "OPEN", NOW.minusSeconds(900));
    }
    private static DeviceTelemetryDtos.Point point(int hour, String value) {
        return new DeviceTelemetryDtos.Point(Instant.parse("2026-09-08T" + String.format("%02d", hour)
                + ":00:00Z"), new BigDecimal(value), "GOOD");
    }
    private static DeviceTelemetryDtos.Response unavailableTelemetry() {
        return telemetry(DeviceTelemetryDtos.Status.UNAVAILABLE, DeviceTelemetryDtos.Freshness.UNKNOWN, List.of());
    }
    private static DeviceTelemetryDtos.Response telemetryWithoutThreshold(List<DeviceTelemetryDtos.Point> points) {
        return telemetryResponse(DeviceTelemetryDtos.Status.AVAILABLE, DeviceTelemetryDtos.Freshness.FRESH,
                points, null);
    }
    private static DeviceTelemetryDtos.Response telemetry(DeviceTelemetryDtos.Status status,
                                                            DeviceTelemetryDtos.Freshness freshness,
                                                            List<DeviceTelemetryDtos.Point> points) {
        return telemetryResponse(status, freshness, points,
                new DeviceTelemetryDtos.Threshold(new BigDecimal("28"), new BigDecimal("35"),
                        "DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1"));
    }
    private static DeviceTelemetryDtos.Response telemetryResponse(DeviceTelemetryDtos.Status status,
                                                                    DeviceTelemetryDtos.Freshness freshness,
                                                                    List<DeviceTelemetryDtos.Point> points,
                                                                    DeviceTelemetryDtos.Threshold threshold) {
        List<DeviceTelemetryDtos.Series> series = points.isEmpty() ? List.of()
                : List.of(new DeviceTelemetryDtos.Series("AC-B1-07", "B1", "HVAC", points, List.of(), freshness));
        return new DeviceTelemetryDtos.Response("TEMPERATURE", "°C", "Asia/Shanghai",
                new DeviceTelemetryDtos.Window(FROM, TO, com.example.smartpark.analytics.telemetry.DeviceTelemetryQuery.Granularity.HOUR),
                status, series, threshold, points.isEmpty() ? null : points.get(points.size() - 1).timestamp(),
                new DeviceTelemetryDtos.Source("OPERATIONS_ANALYTICS_DEMO", "TEMPERATURE", status, "DEMO"), List.of());
    }
}
