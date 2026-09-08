package com.example.smartpark.analytics.energy;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.analytics.sql.SqlAstGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-shape energy query adapter. SQL grammar is composed only from catalog
 * definitions and the closed granularity enum; request values are parameters.
 */
public final class JdbcEnergyTimeSeriesReader implements EnergyTimeSeriesReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcEnergyTimeSeriesReader.class);
    static final int MAX_ROWS = 500;

    private final QueryCostGuard costGuard;
    private final ReadOnlyQueryExecutor executor;
    private final ZoneId timezone;

    public JdbcEnergyTimeSeriesReader(QueryCostGuard costGuard, ReadOnlyQueryExecutor executor, ZoneId timezone) {
        this.costGuard = Objects.requireNonNull(costGuard, "costGuard");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timezone = Objects.requireNonNull(timezone, "timezone");
    }

    @Override
    public Snapshot read(Request request, MetricDefinition metric) {
        try {
            String bucket = request.granularity() == EnergyTimeSeriesQuery.Granularity.HOUR
                    ? "hour_ts" : "stat_date";
            String bucketAlias = request.granularity() == EnergyTimeSeriesQuery.Granularity.HOUR
                    ? "bucket_ts" : "bucket_date";
            String sql = "SELECT building_id, " + bucket + " AS " + bucketAlias + ", "
                    + metric.expression() + " AS metric_value, MAX(hour_ts) AS observed_at FROM "
                    + metric.sourceView()
                    + " WHERE hour_ts >= :from AND hour_ts < :to AND building_id IN (:buildingIds)"
                    + " GROUP BY building_id, " + bucket
                    + " ORDER BY building_id ASC, " + bucket + " ASC LIMIT " + MAX_ROWS;

            ValidatedSql validated = SqlAstGuard.validate(sql);
            Map<String, Object> parameters = new LinkedHashMap<>();
            // QueryCostGuard and ReadOnlyQueryExecutor must receive exactly the same
            // PostgreSQL-compatible timestamptz bindings. The driver cannot infer
            // a JDBC type for java.time.Instant when EXPLAIN runs directly.
            parameters.put("from", request.from().atOffset(ZoneOffset.UTC));
            parameters.put("to", request.to().atOffset(ZoneOffset.UTC));
            parameters.put("buildingIds", request.buildingIds());
            costGuard.estimatedCost(validated.sql(), parameters);
            TabularResult result = executor.execute(validated, parameters);
            List<Row> rows = result.rows().stream()
                    .map(row -> mapRow(result, row, bucketAlias))
                    .toList();
            return Snapshot.available(rows, result.truncated());
        } catch (Exception exception) {
            // Failure details stay server-side; the public DTO only exposes availability.
            LOGGER.warn("Energy time-series query unavailable: exceptionType={}", exception.getClass().getName());
            return Snapshot.unavailable("ENERGY_QUERY_UNAVAILABLE");
        }
    }

    private Row mapRow(TabularResult result, List<Object> row, String bucketAlias) {
        return new Row(text(result, row, "building_id"),
                bucket(result, row, bucketAlias),
                decimal(result, row, "metric_value"),
                instant(result, row, "observed_at"));
    }

    private Instant bucket(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof LocalDate date) return date.atStartOfDay(timezone).toInstant();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay(timezone).toInstant();
        return instant(value);
    }

    private static Object value(TabularResult result, List<Object> row, String column) {
        int index = result.columnNames().indexOf(column);
        return index < 0 || index >= row.size() ? null : row.get(index);
    }

    private static String text(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static Instant instant(TabularResult result, List<Object> row, String column) {
        return instant(value(result, row, column));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return value == null ? null : Instant.parse(value.toString());
    }
}
