package com.example.smartpark.analytics.anomaly;

import java.util.List;

/** A bounded evidence read with explicit availability metadata. */
public record EvidenceResult<T>(List<T> items, boolean available, String failureCode) {
    public EvidenceResult {
        items = List.copyOf(items == null ? List.of() : items);
    }

    public static <T> EvidenceResult<T> available(List<T> items) {
        return new EvidenceResult<>(items, true, null);
    }

    public static <T> EvidenceResult<T> partial(List<T> items, String failureCode) {
        return new EvidenceResult<>(items, true, failureCode);
    }

    public static <T> EvidenceResult<T> unavailable(String failureCode) {
        return new EvidenceResult<>(List.of(), false, failureCode);
    }
}
