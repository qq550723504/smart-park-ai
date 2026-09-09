package com.example.smartpark.analytics.energy;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable application service shared by the dashboard and Operations Analytics metric definitions. */
public final class EnergyTimeSeriesService {
    /** Must match the timezone used by analytics.v_energy_hourly's registered date dimensions. */
    public static final ZoneId FACT_TIMEZONE = ZoneId.of("Asia/Shanghai");

    private static final Set<String> SUPPORTED_METRICS = Set.of(
            "energy_kwh", "energy_baseline_kwh", "energy_deviation_pct");
    private static final Duration MAX_WINDOW = Duration.ofDays(31);
    private static final int MAX_BUILDINGS = 20;
    /** Public request bound kept equal to the governed SQL row cap. */
    private static final int MAX_POINTS = 500;
    private static final int DEFAULT_LOOKBACK_DAYS = 5;

    private final MetricCatalog catalog;
    private final EnergyTimeSeriesReader reader;
    private final Clock clock;
    private final ZoneId timezone;

    public EnergyTimeSeriesService(MetricCatalog catalog, EnergyTimeSeriesReader reader,
                                   Clock clock, ZoneId timezone) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
    }

    public EnergyTimeSeriesDtos.Response query(EnergyTimeSeriesQuery input) {
        Objects.requireNonNull(input, "query");
        String metricName = normalizeMetric(input.metric());
        MetricDefinition metric = catalog.findByName(metricName)
                .filter(definition -> SUPPORTED_METRICS.contains(definition.name()))
                .orElseThrow(() -> new IllegalArgumentException("unsupported energy metric"));
        EnergyTimeSeriesQuery.Granularity granularity = Objects.requireNonNullElse(
                input.granularity(), EnergyTimeSeriesQuery.Granularity.HOUR);
        List<String> buildingIds = normalizeBuildingIds(input.buildingIds());
        EnergyTimeSeriesDtos.Window window = normalizeWindow(input.from(), input.to(), granularity);
        List<Instant> expected = expectedBuckets(window);
        if ((long) expected.size() * buildingIds.size() > MAX_POINTS) {
            throw new IllegalArgumentException("requested energy time-series range is too large");
        }

        EnergyTimeSeriesReader.Snapshot snapshot = reader.read(
                new EnergyTimeSeriesReader.Request(buildingIds, window.from(), window.to(), granularity), metric);
        if (!snapshot.available() || snapshot.rows().isEmpty()) {
            return unavailable(metric, window);
        }

        Map<String, Map<Instant, EnergyTimeSeriesReader.Row>> rowsByBuilding = new LinkedHashMap<>();
        buildingIds.forEach(id -> rowsByBuilding.put(id, new LinkedHashMap<>()));
        for (EnergyTimeSeriesReader.Row row : snapshot.rows().stream()
                .sorted(Comparator.comparing(EnergyTimeSeriesReader.Row::buildingId)
                        .thenComparing(EnergyTimeSeriesReader.Row::bucketTimestamp))
                .toList()) {
            Map<Instant, EnergyTimeSeriesReader.Row> rows = rowsByBuilding.get(row.buildingId());
            if (rows != null && row.bucketTimestamp() != null && row.value() != null) {
                rows.put(row.bucketTimestamp(), row);
            }
        }

        List<EnergyTimeSeriesDtos.Series> series = new ArrayList<>();
        List<EnergyTimeSeriesDtos.Evidence> evidence = new ArrayList<>();
        boolean partial = snapshot.truncated();
        int actualPointCount = 0;
        Instant asOf = null;
        for (String buildingId : buildingIds) {
            Map<Instant, EnergyTimeSeriesReader.Row> rows = rowsByBuilding.get(buildingId);
            List<EnergyTimeSeriesDtos.Point> points = rows.values().stream()
                    .sorted(Comparator.comparing(EnergyTimeSeriesReader.Row::bucketTimestamp))
                    .map(row -> new EnergyTimeSeriesDtos.Point(row.bucketTimestamp(), row.value()))
                    .toList();
            List<Instant> missing = expected.stream().filter(timestamp -> !rows.containsKey(timestamp)).toList();
            actualPointCount += points.size();
            partial |= !missing.isEmpty();
            Instant first = points.isEmpty() ? null : points.get(0).timestamp();
            Instant last = points.isEmpty() ? null : points.get(points.size() - 1).timestamp();
            series.add(new EnergyTimeSeriesDtos.Series(buildingId, points, missing));
            evidence.add(new EnergyTimeSeriesDtos.Evidence(buildingId, expected.size(), points.size(), first, last));
            for (EnergyTimeSeriesReader.Row row : rows.values()) {
                if (row.observedAt() != null && (asOf == null || row.observedAt().isAfter(asOf))) {
                    asOf = row.observedAt();
                }
            }
        }
        if (actualPointCount == 0) return unavailable(metric, window);
        EnergyTimeSeriesDtos.Status status = partial
                ? EnergyTimeSeriesDtos.Status.PARTIAL : EnergyTimeSeriesDtos.Status.AVAILABLE;
        return new EnergyTimeSeriesDtos.Response(metric.name(), metric.unit(), timezone.getId(), window, status,
                series, asOf, new EnergyTimeSeriesDtos.Source("OPERATIONS_ANALYTICS", metric.name(), status), evidence);
    }

    private EnergyTimeSeriesDtos.Response unavailable(MetricDefinition metric, EnergyTimeSeriesDtos.Window window) {
        EnergyTimeSeriesDtos.Status status = EnergyTimeSeriesDtos.Status.UNAVAILABLE;
        return new EnergyTimeSeriesDtos.Response(metric.name(), metric.unit(), timezone.getId(), window, status,
                List.of(), null, new EnergyTimeSeriesDtos.Source("OPERATIONS_ANALYTICS", metric.name(), status), List.of());
    }

    private static String normalizeMetric(String metric) {
        return metric == null || metric.isBlank() ? "energy_kwh" : metric.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeBuildingIds(List<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String item : values == null ? List.<String>of() : values) {
            if (item == null) continue;
            for (String token : item.split(",", -1)) {
                String id = token.trim().toUpperCase(Locale.ROOT);
                if (!id.matches("B[0-9]{1,10}")) {
                    throw new IllegalArgumentException("invalid buildingId");
                }
                ids.add(id);
            }
        }
        if (ids.isEmpty() || ids.size() > MAX_BUILDINGS) {
            throw new IllegalArgumentException("buildingIds must contain 1..20 values");
        }
        return List.copyOf(ids);
    }

    private EnergyTimeSeriesDtos.Window normalizeWindow(Instant requestedFrom, Instant requestedTo,
                                                         EnergyTimeSeriesQuery.Granularity granularity) {
        Instant defaultTo = boundaryAtOrBefore(clock.instant(), granularity);
        Instant to = requestedTo == null ? defaultTo : requestedTo;
        Instant from = requestedFrom == null ? defaultFrom(to, granularity) : requestedFrom;
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("energy time-series window exceeds 31 days");
        }
        if (!isBoundary(from, granularity) || !isBoundary(to, granularity)) {
            throw new IllegalArgumentException("time window must align with granularity");
        }
        return new EnergyTimeSeriesDtos.Window(from, to, granularity);
    }

    private Instant defaultFrom(Instant to, EnergyTimeSeriesQuery.Granularity granularity) {
        if (granularity == EnergyTimeSeriesQuery.Granularity.HOUR) {
            return to.minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS);
        }
        return to.atZone(timezone).minusDays(DEFAULT_LOOKBACK_DAYS).toInstant();
    }

    private Instant boundaryAtOrBefore(Instant value, EnergyTimeSeriesQuery.Granularity granularity) {
        if (granularity == EnergyTimeSeriesQuery.Granularity.HOUR) {
            return value.truncatedTo(ChronoUnit.HOURS);
        }
        return value.atZone(timezone).toLocalDate().atStartOfDay(timezone).toInstant();
    }

    private boolean isBoundary(Instant value, EnergyTimeSeriesQuery.Granularity granularity) {
        return boundaryAtOrBefore(value, granularity).equals(value);
    }

    private List<Instant> expectedBuckets(EnergyTimeSeriesDtos.Window window) {
        List<Instant> result = new ArrayList<>();
        if (window.granularity() == EnergyTimeSeriesQuery.Granularity.HOUR) {
            for (Instant value = window.from(); value.isBefore(window.to()); value = value.plus(1, ChronoUnit.HOURS)) {
                result.add(value);
            }
            return result;
        }
        for (ZonedDateTime value = window.from().atZone(timezone);
             value.toInstant().isBefore(window.to()); value = value.plusDays(1)) {
            result.add(value.toInstant());
        }
        return result;
    }

    public static final class EnergyTimeSeriesUnavailableException extends RuntimeException {
        public EnergyTimeSeriesUnavailableException(String message) {
            super(message);
        }
    }
}
