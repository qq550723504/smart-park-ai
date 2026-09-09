package com.example.smartpark.analytics.report;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Server-validated input for the fixed operations daily report. */
public record OperationsReportRequest(String reportType, TimeWindow timeWindow, String timezone) {
    public static final String DAILY = "OPERATIONS_DAILY";
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    public OperationsReportRequest {
        reportType = requireText(reportType, "reportType");
        if (!DAILY.equals(reportType)) throw new IllegalArgumentException("unsupported reportType");
        Objects.requireNonNull(timeWindow, "timeWindow");
        timezone = requireText(timezone, "timezone");
        ZoneId.of(timezone);
    }

    public record TimeWindow(Instant fromInclusive, Instant toExclusive) {
        public TimeWindow {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (!fromInclusive.isBefore(toExclusive)) throw new IllegalArgumentException("timeWindow must be ordered");
            if (java.time.Duration.between(fromInclusive, toExclusive).compareTo(java.time.Duration.ofDays(31)) > 0) {
                throw new IllegalArgumentException("timeWindow must not exceed 31 days");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
