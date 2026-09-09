package com.example.smartpark.analytics.health;

import com.example.smartpark.analytics.telemetry.DeviceTelemetryDtos;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryQuery;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryService;
import com.example.smartpark.analytics.telemetry.TelemetryCatalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Deterministic, explainable health state. No numeric score or prediction is produced. */
public final class DeviceHealthService {
    private static final Duration SNAPSHOT_FRESHNESS = Duration.ofHours(6);

    private final DeviceHealthFactsReader factsReader;
    private final DeviceTelemetryService telemetry;
    private final TelemetryCatalog catalog;
    private final Clock clock;

    public DeviceHealthService(DeviceHealthFactsReader factsReader, DeviceTelemetryService telemetry,
                               TelemetryCatalog catalog, Clock clock) {
        this.factsReader = Objects.requireNonNull(factsReader, "factsReader");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeviceHealthDtos.Response assess(String rawDeviceId) {
        String deviceId = normalizeDeviceId(rawDeviceId);
        DeviceHealthFactsReader.Facts facts = factsReader.read(deviceId);
        if (!facts.available()) return unavailable(deviceId, "设备快照与告警数据不可用");
        if (facts.device() == null) throw new NoSuchElementException("unknown device");
        DeviceHealthFactsReader.DeviceFact device = facts.device();
        if (facts.activeAlerts().stream().anyMatch(alert ->
                !deviceId.equals(alert.deviceId()) || !device.buildingId().equals(alert.buildingId()))) {
            throw new DeviceHealthUnavailableException("device health evidence scope mismatch");
        }

        List<String> reasons = new ArrayList<>();
        List<DeviceHealthDtos.Evidence> evidence = new ArrayList<>();
        List<DeviceHealthDtos.Source> sources = new ArrayList<>();
        sources.add(new DeviceHealthDtos.Source("DEVICE_SNAPSHOT", DeviceHealthDtos.Availability.AVAILABLE));
        sources.add(new DeviceHealthDtos.Source("ALERT_FACT", DeviceHealthDtos.Availability.AVAILABLE));
        evidence.add(new DeviceHealthDtos.Evidence("CONNECTIVITY", "device-snapshot:" + deviceId,
                device.snapshotAt(), "设备状态 " + safeStatus(device.status())));

        int severity = 0;
        boolean partial = false;
        Instant now = clock.instant();
        boolean snapshotFresh = device.snapshotAt() != null && !device.snapshotAt().isAfter(now)
                && Duration.between(device.snapshotAt(), now).compareTo(SNAPSHOT_FRESHNESS) <= 0;
        if (!snapshotFresh) {
            partial = true;
            reasons.add("设备状态快照已过期，不能据此判断当前健康");
        } else if ("OFFLINE".equalsIgnoreCase(device.status())) {
            severity = 4;
            reasons.add("设备当前离线");
        } else if ("DEGRADED".equalsIgnoreCase(device.status())) {
            severity = Math.max(severity, 1);
            reasons.add("设备快照状态为 DEGRADED");
        }

        for (DeviceHealthFactsReader.AlertFact alert : facts.activeAlerts()) {
            int alertSeverity = switch (safeStatus(alert.riskLevel())) {
                case "HIGH" -> 4;
                case "MEDIUM" -> 3;
                case "LOW" -> 1;
                default -> 1;
            };
            severity = Math.max(severity, alertSeverity);
            reasons.add("存在 " + safeStatus(alert.riskLevel()) + " 风险活动告警 " + alert.alertId());
            evidence.add(new DeviceHealthDtos.Evidence("ACTIVE_ALERT", "alert:" + alert.alertId(),
                    alert.occurredAt(), safeStatus(alert.category()) + " · " + safeStatus(alert.riskLevel())));
        }

        DeviceTelemetryDtos.Response telemetryResponse = null;
        var definition = catalog.availableForDeviceType(device.deviceType());
        if (definition.isPresent()) {
            telemetryResponse = telemetry.query(new DeviceTelemetryQuery(definition.get().telemetryType(),
                    List.of(deviceId), null, null, DeviceTelemetryQuery.Granularity.HOUR));
            DeviceHealthDtos.Availability telemetryAvailability = switch (telemetryResponse.status()) {
                case AVAILABLE -> DeviceHealthDtos.Availability.AVAILABLE;
                case PARTIAL -> DeviceHealthDtos.Availability.PARTIAL;
                case UNAVAILABLE -> DeviceHealthDtos.Availability.UNAVAILABLE;
            };
            sources.add(new DeviceHealthDtos.Source(telemetryResponse.source().system(), telemetryAvailability));
            partial |= telemetryResponse.status() != DeviceTelemetryDtos.Status.AVAILABLE;
            if (telemetryResponse.status() == DeviceTelemetryDtos.Status.PARTIAL) {
                reasons.add("设备遥测窗口不完整，不能据此确认健康");
            }
            DeviceTelemetryDtos.Series series = telemetryResponse.series().stream().findFirst().orElse(null);
            if (series == null || series.points().isEmpty()) {
                reasons.add("没有可用于当前健康判断的设备遥测");
            } else if (series.freshness() != DeviceTelemetryDtos.Freshness.FRESH) {
                partial = true;
                reasons.add("设备遥测已过期，未用于阈值判断");
            } else if (telemetryResponse.threshold() == null) {
                partial = true;
                reasons.add("遥测可用但没有已登记阈值，未自动推导异常");
            } else {
                severity = applyThresholdEvidence(telemetryResponse, series, severity, reasons, evidence);
            }
        } else {
            partial = true;
            reasons.add("该设备类型没有已接入的遥测能力");
        }

        DeviceHealthDtos.HealthStatus status;
        if (severity == 4) status = DeviceHealthDtos.HealthStatus.CRITICAL;
        else if (severity == 3) status = DeviceHealthDtos.HealthStatus.DEGRADED;
        else if (severity > 0) status = DeviceHealthDtos.HealthStatus.ATTENTION;
        else if (snapshotFresh && telemetryResponse != null
                && telemetryResponse.status() == DeviceTelemetryDtos.Status.AVAILABLE
                && telemetryResponse.threshold() != null
                && telemetryResponse.series().stream().anyMatch(series ->
                    series.freshness() == DeviceTelemetryDtos.Freshness.FRESH && !series.points().isEmpty())) {
            status = DeviceHealthDtos.HealthStatus.HEALTHY;
            reasons.add("设备在线，且已登记遥测信号未触发阈值证据");
        } else {
            status = DeviceHealthDtos.HealthStatus.UNKNOWN;
        }
        if (status == DeviceHealthDtos.HealthStatus.UNKNOWN && reasons.isEmpty()) {
            reasons.add("证据不足，不能推断设备健康");
        }
        DeviceHealthDtos.Availability availability = status == DeviceHealthDtos.HealthStatus.UNKNOWN
                && evidence.isEmpty() ? DeviceHealthDtos.Availability.UNAVAILABLE
                : partial ? DeviceHealthDtos.Availability.PARTIAL : DeviceHealthDtos.Availability.AVAILABLE;
        Instant asOf = latest(device.snapshotAt(), facts.activeAlerts().stream()
                .map(DeviceHealthFactsReader.AlertFact::occurredAt).toList(),
                telemetryResponse == null ? null : telemetryResponse.asOf());
        return new DeviceHealthDtos.Response(deviceId, device.buildingId(), device.deviceType(), status,
                availability, distinct(reasons), evidence, sources, asOf);
    }

    public Optional<DeviceHealthDtos.Response> assessByAlertId(String rawAlertId) {
        if (rawAlertId == null || !rawAlertId.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
            throw new IllegalArgumentException("invalid alertId");
        }
        String deviceId = factsReader.findDeviceIdByAlertId(rawAlertId.trim());
        return deviceId == null ? Optional.empty() : Optional.of(assess(deviceId));
    }

    private static int applyThresholdEvidence(DeviceTelemetryDtos.Response response,
                                               DeviceTelemetryDtos.Series series, int severity,
                                               List<String> reasons,
                                               List<DeviceHealthDtos.Evidence> evidence) {
        List<DeviceTelemetryDtos.Point> ordered = series.points().stream()
                .sorted(Comparator.comparing(DeviceTelemetryDtos.Point::timestamp)).toList();
        DeviceTelemetryDtos.Point latest = ordered.get(ordered.size() - 1);
        BigDecimal critical = response.threshold().criticalAbove();
        BigDecimal attention = response.threshold().attentionAbove();
        if (latest.value().compareTo(critical) > 0) {
            reasons.add("最新温度超过已登记的 demo critical 阈值");
            evidence.add(thresholdEvidence(response, latest, "CRITICAL"));
            return 4;
        }
        List<DeviceTelemetryDtos.Point> tail = ordered.subList(Math.max(0, ordered.size() - 3), ordered.size());
        boolean consecutiveHours = tail.size() == 3
                && Duration.between(tail.get(0).timestamp(), tail.get(1).timestamp()).equals(Duration.ofHours(1))
                && Duration.between(tail.get(1).timestamp(), tail.get(2).timestamp()).equals(Duration.ofHours(1));
        if (consecutiveHours && tail.stream().allMatch(point -> point.value().compareTo(attention) > 0)) {
            reasons.add("最近 3 个温度点持续超过已登记的 demo attention 阈值");
            tail.forEach(point -> evidence.add(thresholdEvidence(response, point, "SUSTAINED")));
            return Math.max(severity, 3);
        }
        if (latest.value().compareTo(attention) > 0) {
            reasons.add("最新温度超过已登记的 demo attention 阈值");
            evidence.add(thresholdEvidence(response, latest, "ATTENTION"));
            return Math.max(severity, 1);
        }
        return severity;
    }

    private static DeviceHealthDtos.Evidence thresholdEvidence(DeviceTelemetryDtos.Response response,
                                                                 DeviceTelemetryDtos.Point point,
                                                                 String level) {
        return new DeviceHealthDtos.Evidence("TELEMETRY_THRESHOLD",
                "telemetry:" + response.telemetryType() + ":" + point.timestamp(), point.timestamp(),
                level + " · " + point.value() + " " + response.unit() + " · " + response.threshold().source());
    }

    private DeviceHealthDtos.Response unavailable(String deviceId, String reason) {
        return new DeviceHealthDtos.Response(deviceId, null, null, DeviceHealthDtos.HealthStatus.UNKNOWN,
                DeviceHealthDtos.Availability.UNAVAILABLE, List.of(reason), List.of(),
                List.of(new DeviceHealthDtos.Source("DEVICE_HEALTH", DeviceHealthDtos.Availability.UNAVAILABLE)),
                clock.instant());
    }

    private static String normalizeDeviceId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("deviceId is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) throw new IllegalArgumentException("invalid deviceId");
        return normalized;
    }
    private static String safeStatus(String value) { return value == null ? "UNKNOWN" : value.toUpperCase(Locale.ROOT); }
    private static List<String> distinct(List<String> values) { return List.copyOf(new LinkedHashSet<>(values)); }
    private static Instant latest(Instant first, List<Instant> values, Instant last) {
        Instant result = first;
        for (Instant value : values) if (value != null && (result == null || value.isAfter(result))) result = value;
        if (last != null && (result == null || last.isAfter(result))) result = last;
        return result;
    }

    public static final class DeviceHealthUnavailableException extends RuntimeException {
        public DeviceHealthUnavailableException(String message) { super(message); }
    }
}
