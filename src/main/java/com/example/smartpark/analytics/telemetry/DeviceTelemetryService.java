package com.example.smartpark.analytics.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class DeviceTelemetryService {
    public static final ZoneId FACT_TIMEZONE = ZoneId.of("Asia/Shanghai");
    static final Duration MAX_WINDOW = Duration.ofDays(7);
    static final Duration FRESHNESS_LIMIT = Duration.ofHours(2);
    static final int MAX_DEVICES = 10;
    static final int MAX_POINTS = 500;
    private static final Duration DEFAULT_LOOKBACK = Duration.ofHours(24);

    private final TelemetryCatalog catalog;
    private final DeviceTelemetryReader reader;
    private final Clock clock;
    private final ZoneId timezone;

    public DeviceTelemetryService(TelemetryCatalog catalog, DeviceTelemetryReader reader,
                                  Clock clock, ZoneId timezone) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
    }

    public DeviceTelemetryDtos.Response query(DeviceTelemetryQuery input) {
        Objects.requireNonNull(input, "query");
        TelemetryCatalog.Definition definition = catalog.resolve(input.telemetryType());
        DeviceTelemetryQuery.Granularity granularity = Objects.requireNonNullElse(
                input.granularity(), DeviceTelemetryQuery.Granularity.HOUR);
        List<String> deviceIds = normalizeDeviceIds(input.deviceIds());
        DeviceTelemetryDtos.Window window = normalizeWindow(input.from(), input.to(), granularity);
        List<Instant> expected = expectedBuckets(window);
        if ((long) expected.size() * deviceIds.size() > MAX_POINTS) {
            throw new IllegalArgumentException("requested telemetry range is too large");
        }
        if (definition.capabilityStatus() == TelemetryCatalog.CapabilityStatus.NOT_READY) {
            return unavailable(definition, window);
        }

        DeviceTelemetryReader.Snapshot snapshot = reader.read(
                new DeviceTelemetryReader.Request(deviceIds, window.from(), window.to(), granularity), definition);
        if (!snapshot.available() || snapshot.truncated()) return unavailable(definition, window);

        Map<String, DeviceTelemetryReader.DeviceDescriptor> descriptors = new LinkedHashMap<>();
        snapshot.devices().forEach(device -> descriptors.put(device.deviceId(), device));
        List<String> missingDevices = deviceIds.stream().filter(id -> !descriptors.containsKey(id)).toList();
        if (!missingDevices.isEmpty()) throw new NoSuchElementException("unknown device");
        if (descriptors.values().stream().anyMatch(device ->
                !definition.allowedDeviceTypes().contains(device.deviceType().toUpperCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("telemetryType is not allowed for device type");
        }

        Map<String, Map<Instant, DeviceTelemetryReader.Row>> rowsByDevice = new LinkedHashMap<>();
        deviceIds.forEach(id -> rowsByDevice.put(id, new LinkedHashMap<>()));
        snapshot.rows().stream()
                .sorted(Comparator.comparing(DeviceTelemetryReader.Row::deviceId)
                        .thenComparing(DeviceTelemetryReader.Row::bucketTimestamp))
                .forEach(row -> {
                    Map<Instant, DeviceTelemetryReader.Row> rows = rowsByDevice.get(row.deviceId());
                    DeviceTelemetryReader.DeviceDescriptor descriptor = descriptors.get(row.deviceId());
                    if (descriptor != null && (!descriptor.buildingId().equals(row.buildingId())
                            || !descriptor.deviceType().equals(row.deviceType()))) {
                        throw new TelemetryUnavailableException("telemetry identity mismatch");
                    }
                    if (rows != null && row.bucketTimestamp() != null && row.value() != null) {
                        DeviceTelemetryReader.Row previous = rows.putIfAbsent(row.bucketTimestamp(), row);
                        if (previous != null) {
                            throw new TelemetryUnavailableException("duplicate telemetry bucket");
                        }
                    }
                });

        boolean partial = false;
        int total = 0;
        Instant asOf = null;
        List<DeviceTelemetryDtos.Series> series = new ArrayList<>();
        List<DeviceTelemetryDtos.Evidence> evidence = new ArrayList<>();
        for (String deviceId : deviceIds) {
            DeviceTelemetryReader.DeviceDescriptor descriptor = descriptors.get(deviceId);
            Map<Instant, DeviceTelemetryReader.Row> rows = rowsByDevice.get(deviceId);
            List<DeviceTelemetryDtos.Point> points = rows.values().stream()
                    .sorted(Comparator.comparing(DeviceTelemetryReader.Row::bucketTimestamp))
                    .map(row -> new DeviceTelemetryDtos.Point(row.bucketTimestamp(), row.value(), row.quality()))
                    .toList();
            List<Instant> missing = expected.stream().filter(timestamp -> !rows.containsKey(timestamp)).toList();
            partial |= !missing.isEmpty();
            total += points.size();
            Instant first = points.isEmpty() ? null : points.get(0).timestamp();
            Instant last = points.isEmpty() ? null : points.get(points.size() - 1).timestamp();
            DeviceTelemetryDtos.Freshness freshness = freshness(last);
            series.add(new DeviceTelemetryDtos.Series(deviceId, descriptor.buildingId(), descriptor.deviceType(),
                    points, missing, freshness));
            evidence.add(new DeviceTelemetryDtos.Evidence(deviceId, expected.size(), points.size(), first, last));
            for (DeviceTelemetryReader.Row row : rows.values()) {
                if (row.observedAt() != null && (asOf == null || row.observedAt().isAfter(asOf))) asOf = row.observedAt();
            }
        }
        if (total == 0) return unavailable(definition, window);
        DeviceTelemetryDtos.Status status = partial
                ? DeviceTelemetryDtos.Status.PARTIAL : DeviceTelemetryDtos.Status.AVAILABLE;
        TelemetryCatalog.Threshold threshold = definition.threshold();
        DeviceTelemetryDtos.Threshold responseThreshold = threshold == null ? null
                : new DeviceTelemetryDtos.Threshold(threshold.attentionAbove(), threshold.criticalAbove(), threshold.source());
        return new DeviceTelemetryDtos.Response(definition.telemetryType(), definition.unit(), timezone.getId(),
                window, status, series, responseThreshold, asOf,
                new DeviceTelemetryDtos.Source(definition.sourceSystem(), definition.telemetryType(), status,
                        definition.datasetKind()), evidence);
    }

    public List<TelemetryCatalog.Definition> capabilities() {
        return catalog.all();
    }

    private DeviceTelemetryDtos.Response unavailable(TelemetryCatalog.Definition definition,
                                                       DeviceTelemetryDtos.Window window) {
        DeviceTelemetryDtos.Status status = DeviceTelemetryDtos.Status.UNAVAILABLE;
        return new DeviceTelemetryDtos.Response(definition.telemetryType(), definition.unit(), timezone.getId(),
                window, status, List.of(), null, null,
                new DeviceTelemetryDtos.Source(definition.sourceSystem(), definition.telemetryType(), status,
                        definition.datasetKind()), List.of());
    }

    private static List<String> normalizeDeviceIds(List<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String item : values == null ? List.<String>of() : values) {
            if (item == null) continue;
            for (String token : item.split(",", -1)) {
                String id = token.trim().toUpperCase(Locale.ROOT);
                if (!id.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) throw new IllegalArgumentException("invalid deviceId");
                ids.add(id);
            }
        }
        if (ids.isEmpty() || ids.size() > MAX_DEVICES) {
            throw new IllegalArgumentException("deviceIds must contain 1..10 values");
        }
        return List.copyOf(ids);
    }

    private DeviceTelemetryDtos.Window normalizeWindow(Instant requestedFrom, Instant requestedTo,
                                                         DeviceTelemetryQuery.Granularity granularity) {
        Instant defaultTo = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant to = requestedTo == null ? defaultTo : requestedTo;
        Instant from = requestedFrom == null ? to.minus(DEFAULT_LOOKBACK) : requestedFrom;
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("telemetry window exceeds 7 days");
        }
        if (!from.equals(from.truncatedTo(ChronoUnit.HOURS)) || !to.equals(to.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("time window must align with granularity");
        }
        return new DeviceTelemetryDtos.Window(from, to, granularity);
    }

    private static List<Instant> expectedBuckets(DeviceTelemetryDtos.Window window) {
        List<Instant> result = new ArrayList<>();
        for (Instant value = window.from(); value.isBefore(window.to()); value = value.plus(1, ChronoUnit.HOURS)) {
            result.add(value);
        }
        return result;
    }

    private DeviceTelemetryDtos.Freshness freshness(Instant lastObservedAt) {
        if (lastObservedAt == null) return DeviceTelemetryDtos.Freshness.UNKNOWN;
        Instant now = clock.instant();
        if (lastObservedAt.isAfter(now)) return DeviceTelemetryDtos.Freshness.UNKNOWN;
        return Duration.between(lastObservedAt, now).compareTo(FRESHNESS_LIMIT) <= 0
                ? DeviceTelemetryDtos.Freshness.FRESH : DeviceTelemetryDtos.Freshness.STALE;
    }

    public static final class TelemetryUnavailableException extends RuntimeException {
        public TelemetryUnavailableException(String message) { super(message); }
    }
}
