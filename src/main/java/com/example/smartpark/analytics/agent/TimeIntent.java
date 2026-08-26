package com.example.smartpark.analytics.agent;

import java.time.LocalDate;
import java.util.Objects;

record TimeIntent(
        String sourceText,
        Kind kind,
        long amount,
        Unit unit,
        LocalDate fromDate,
        LocalDate toDate,
        DayPart dayPart) {

    TimeIntent {
        if (sourceText == null || sourceText.isBlank()) {
            throw new IllegalArgumentException("sourceText must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        if (amount < 0 || (kind == Kind.ROLLING && amount == 0)) {
            throw new IllegalArgumentException("amount must be positive for rolling intents");
        }
        if (kind != Kind.ROLLING && amount != 0) {
            throw new IllegalArgumentException("amount is only valid for rolling intents");
        }
        if (kind == Kind.ROLLING && unit == null) {
            throw new IllegalArgumentException("rolling intent requires a unit");
        }
        if (kind != Kind.ROLLING && unit != null) {
            throw new IllegalArgumentException("unit is only valid for rolling intents");
        }
        if (kind == Kind.DATE_RANGE) {
            if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
                throw new IllegalArgumentException("date range requires ordered date endpoints");
            }
        } else if (kind == Kind.SINGLE_DATE || kind == Kind.QUALIFIED_DAY) {
            if (fromDate == null || toDate != null) {
                throw new IllegalArgumentException("single-day intent requires one date");
            }
        } else if (kind == Kind.CALENDAR_PERIOD) {
            if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
                throw new IllegalArgumentException("calendar intent requires ordered date endpoints");
            }
        } else if (kind == Kind.DAY_PART) {
            if (fromDate == null || toDate != null) {
                throw new IllegalArgumentException("day-part intent requires one date");
            }
        } else if (fromDate != null || toDate != null) {
            throw new IllegalArgumentException("dates are not valid for this intent kind");
        }
        if (kind == Kind.DAY_PART && dayPart == null) {
            throw new IllegalArgumentException("day-part intent requires a day part");
        }
        if (kind != Kind.DAY_PART && dayPart != null) {
            throw new IllegalArgumentException("dayPart is only valid for day-part intents");
        }
    }

    enum Kind { ROLLING, SINGLE_DATE, DATE_RANGE, CALENDAR_PERIOD, QUALIFIED_DAY, DAY_PART }

    enum Unit { HOUR, DAY, WEEK, MONTH, QUARTER, YEAR, HALF_YEAR }

    enum DayPart { MORNING, AFTERNOON }
}
