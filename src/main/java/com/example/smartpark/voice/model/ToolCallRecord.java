package com.example.smartpark.voice.model;

import java.util.Objects;

/**
 * One real read-only tool invocation of this turn. argumentSummary and
 * resultDigest are display-safe: identifiers, numeric readings and explicit
 * errors only — no raw payloads, no provider internals, no secrets.
 */
public record ToolCallRecord(String toolName, String argumentSummary, String resultDigest) {

    public ToolCallRecord {
        Objects.requireNonNull(toolName, "toolName");
        if (argumentSummary == null || argumentSummary.isBlank()) {
            throw new IllegalArgumentException("argumentSummary must not be blank");
        }
        resultDigest = resultDigest == null ? "" : resultDigest;
    }
}
