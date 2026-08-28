package com.example.smartpark.execution.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed union of display payloads. No arbitrary top-level maps are allowed so the
 * frontend can render a discriminated union that mirrors these types exactly.
 * Payload factories reject credential-like keys before anything reaches the wire.
 */
public sealed interface DisplayPayload
        permits DisplayPayload.TextPayload,
                DisplayPayload.ToolCallPayload,
                DisplayPayload.ExpertHandoffPayload,
                DisplayPayload.SqlPayload,
                DisplayPayload.ChartPayload,
                DisplayPayload.AudioPayload,
                DisplayPayload.ErrorPayload {

    Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "password", "secret", "token", "authorization",
            "credential", "credentials", "connectionstring", "connection_string",
            "privatekey", "private_key", "cookie", "sessionid", "session_id");

    static void rejectSensitiveKeys(Map<String, ?> entries) {
        for (String key : Objects.requireNonNull(entries, "entries").keySet()) {
            if (SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("sensitive display field is not allowed: [redacted]");
            }
            if (key.isBlank()) {
                throw new IllegalArgumentException("display field names must not be blank");
            }
        }
    }

    record TextPayload(String text, boolean partial) implements DisplayPayload {
        public TextPayload {
            Objects.requireNonNull(text, "text");
        }
    }

    record ToolCallPayload(String toolName, Map<String, String> safeArguments, String resultSummary)
            implements DisplayPayload {
        public ToolCallPayload {
            Objects.requireNonNull(toolName, "toolName");
            if (toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            safeArguments = Map.copyOf(Objects.requireNonNullElse(safeArguments, Map.of()));
            rejectSensitiveKeys(safeArguments);
            resultSummary = Objects.requireNonNull(resultSummary, "resultSummary");
        }

        public static ToolCallPayload of(String toolName, Map<String, String> safeArguments) {
            return new ToolCallPayload(toolName, safeArguments, "");
        }
    }

    record ExpertHandoffPayload(String domain, String direction, String findingStatus)
            implements DisplayPayload {
        public ExpertHandoffPayload {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(findingStatus, "findingStatus");
        }
    }

    /** Carries only whitelisted-view SQL plus parameter names; never credentials or raw results. */
    record SqlPayload(String safeSql, List<String> parameterNames, String validationStatus)
            implements DisplayPayload {
        public SqlPayload {
            Objects.requireNonNull(safeSql, "safeSql");
            parameterNames = List.copyOf(Objects.requireNonNullElse(parameterNames, List.of()));
            Objects.requireNonNull(validationStatus, "validationStatus");
        }
    }

    /** Mirrors the validated ChartSpec contract without carrying raw model configuration. */
    record ChartPayload(String type, String title, String xField, List<String> yFields,
                        String seriesField, String unit, String orientation, boolean stacked,
                        Double targetValue, String coordinateXField, String coordinateYField) implements DisplayPayload {
        public ChartPayload {
            Objects.requireNonNull(type, "type");
            if (!Set.of("LINE", "BAR", "TABLE", "KPI", "STACKED_BAR", "HEATMAP",
                    "CALENDAR_HEATMAP", "SCATTER", "GAUGE", "MAP").contains(type)) {
                throw new IllegalArgumentException("unsupported chart type: " + type);
            }
            title = Objects.requireNonNull(title, "title");
            xField = Objects.requireNonNull(xField, "xField");
            yFields = List.copyOf(Objects.requireNonNullElse(yFields, List.of()));
            seriesField = Objects.requireNonNull(seriesField, "seriesField");
            unit = Objects.requireNonNull(unit, "unit");
            orientation = orientation == null || orientation.isBlank()
                    ? "VERTICAL" : orientation.strip().toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("VERTICAL", "HORIZONTAL").contains(orientation)) {
                throw new IllegalArgumentException("unsupported chart orientation: " + orientation);
            }
            if (targetValue != null && (!Double.isFinite(targetValue) || targetValue < 0)) {
                throw new IllegalArgumentException("targetValue must be finite and non-negative");
            }
            coordinateXField = coordinateXField == null ? "" : coordinateXField.strip();
            coordinateYField = coordinateYField == null ? "" : coordinateYField.strip();
        }

        ChartPayload(String type, String title, String xField, List<String> yFields,
                     String seriesField, String unit) {
            this(type, title, xField, yFields, seriesField, unit,
                    "VERTICAL", false, null, "", "");
        }
    }

    /** Playback state only: duration/state metadata, never original input audio. */
    record AudioPayload(String state, Integer durationMs) implements DisplayPayload {
        public AudioPayload {
            Objects.requireNonNull(state, "state");
        }
    }

    /** Failure payload restricted to the four contract fields; no vendor bodies or stacks. */
    record ErrorPayload(ExecutionStage stage, String errorCode, boolean retryable, String safeMessage)
            implements DisplayPayload {
        public ErrorPayload {
            Objects.requireNonNull(stage, "stage");
            errorCode = Objects.requireNonNull(errorCode, "errorCode");
            safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
        }
    }

    static TextPayload text(String text, boolean partial) {
        return new TextPayload(text, partial);
    }

    static ToolCallPayload toolCall(String toolName, Map<String, String> safeArguments) {
        return ToolCallPayload.of(toolName, safeArguments);
    }

    static ErrorPayload error(ExecutionStage stage, String errorCode, boolean retryable, String safeMessage) {
        return new ErrorPayload(stage, errorCode, retryable, safeMessage);
    }
}
