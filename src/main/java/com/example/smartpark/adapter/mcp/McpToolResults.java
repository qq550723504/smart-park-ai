package com.example.smartpark.adapter.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class McpToolResults {
    public static final String NOTICE = "Mock park data only. Read-only; no device control.";
    public static final String INVALID_MESSAGE = "Invalid tool argument.";
    public static final String NOT_FOUND_MESSAGE = "Requested park record was not found.";
    public static final String INTERNAL_MESSAGE = "Tool execution failed.";

    private McpToolResults() { }

    public enum ErrorCode { INVALID_ARGUMENT, NOT_FOUND, INTERNAL_ERROR }
    public record McpError(ErrorCode code, String message) {
        public McpError {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
        }
    }
    public record AlertData(String alertId, String parkId, String buildingId, String deviceId,
            String classification, String riskHint, Instant occurredAt) { }
    public record AlertLookupResult(boolean ok, AlertData data, McpError error, String notice) {
        public AlertLookupResult {
            notice = Objects.requireNonNull(notice, "notice");
            if (ok && (data == null || error != null)) throw new IllegalArgumentException("successful result requires data only");
            if (!ok && (data != null || error == null)) throw new IllegalArgumentException("failed result requires error only");
        }
    }
    public record EnergyData(String meterId, String parkId, String buildingId, Instant measuredAt,
            double currentKwh, double baselineKwh, double peakDemandKw,
            double varianceKwh, double varianceRatio) { }
    public record EnergyLookupResult(boolean ok, EnergyData data, McpError error, String notice) {
        public EnergyLookupResult {
            notice = Objects.requireNonNull(notice, "notice");
            if (ok && (data == null || error != null)) throw new IllegalArgumentException("successful result requires data only");
            if (!ok && (data != null || error == null)) throw new IllegalArgumentException("failed result requires error only");
        }
    }
    public record KnowledgeMatchData(String documentId, String title, String domain,
            List<String> tags, double score, Instant updatedAt) {
        public KnowledgeMatchData {
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        }
    }
    public record KnowledgeData(String domain, List<KnowledgeMatchData> matches) {
        public KnowledgeData {
            domain = Objects.requireNonNull(domain, "domain");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        }
    }
    public record KnowledgeSearchResult(boolean ok, KnowledgeData data, McpError error, String notice) {
        public KnowledgeSearchResult {
            notice = Objects.requireNonNull(notice, "notice");
            if (ok && (data == null || error != null)) throw new IllegalArgumentException("successful result requires data only");
            if (!ok && (data != null || error == null)) throw new IllegalArgumentException("failed result requires error only");
        }
    }

    public static McpError invalidArgument() {
        return new McpError(ErrorCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }

    public static McpError notFound() {
        return new McpError(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    public static McpError internalError() {
        return new McpError(ErrorCode.INTERNAL_ERROR, INTERNAL_MESSAGE);
    }
}
