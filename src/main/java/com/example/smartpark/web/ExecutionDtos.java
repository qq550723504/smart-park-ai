package com.example.smartpark.web;

import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only public DTOs for the unified execution trace; never exposes publish endpoints. */
final class ExecutionDtos {

    private ExecutionDtos() {
    }

    record ExecutionRunDto(String status, long totalEvents) {
        static ExecutionRunDto of(String status, int totalEvents) {
            return new ExecutionRunDto(status, totalEvents);
        }
    }

    record ExecutionEventDto(
            UUID eventId,
            UUID runId,
            long sequence,
            Instant timestamp,
            String scenario,
            String actor,
            String stage,
            String eventType,
            String status,
            String safeSummary,
            PayloadDto displayPayload) {

        static ExecutionEventDto from(ExecutionEvent event) {
            return new ExecutionEventDto(
                    event.eventId(),
                    event.runId(),
                    event.sequence(),
                    event.timestamp(),
                    event.scenario().name(),
                    event.actor(),
                    event.stage().name(),
                    event.eventType().name(),
                    event.status().name(),
                    event.safeSummary(),
                    PayloadDto.from(event.displayPayload()));
        }
    }

    /** Mirrors the sealed DisplayPayload union with a stable JSON discriminator. */
    sealed interface PayloadDto permits PayloadDto.TextDto, PayloadDto.ToolCallDto, PayloadDto.ExpertHandoffDto,
            PayloadDto.SqlDto, PayloadDto.ChartDto, PayloadDto.TimeRangeDto, PayloadDto.AudioDto, PayloadDto.ErrorDto {
        String payloadType();

        static PayloadDto from(DisplayPayload payload) {
            if (payload == null) {
                return null;
            }
            if (payload instanceof DisplayPayload.TextPayload text) {
                return new PayloadDto.TextDto(text.text(), text.partial());
            }
            if (payload instanceof DisplayPayload.ToolCallPayload toolCall) {
                return new PayloadDto.ToolCallDto(toolCall.toolName(), toolCall.safeArguments(), toolCall.resultSummary());
            }
            if (payload instanceof DisplayPayload.ExpertHandoffPayload handoff) {
                return new PayloadDto.ExpertHandoffDto(handoff.domain(), handoff.direction(), handoff.findingStatus());
            }
            if (payload instanceof DisplayPayload.SqlPayload sql) {
                return new PayloadDto.SqlDto(sql.safeSql(), sql.parameterNames(), sql.validationStatus());
            }
            if (payload instanceof DisplayPayload.ChartPayload chart) {
                return new PayloadDto.ChartDto(chart.type(), chart.title(), chart.xField(), chart.yFields(),
                        chart.seriesField(), chart.unit());
            }
            if (payload instanceof DisplayPayload.AudioPayload audio) {
                return new PayloadDto.AudioDto(audio.state(), audio.durationMs());
            }
            if (payload instanceof DisplayPayload.TimeRangePayload timeRange) {
                return new PayloadDto.TimeRangeDto(timeRange.status(), timeRange.fromInclusive(),
                        timeRange.toExclusive(), timeRange.source(), timeRange.explanation(), timeRange.empty());
            }
            if (payload instanceof DisplayPayload.ErrorPayload error) {
                return new PayloadDto.ErrorDto(error.stage().name(), error.errorCode(), error.retryable(), error.safeMessage());
            }
            throw new IllegalArgumentException("unsupported payload union member");
        }

        record TextDto(String payloadType, String text, boolean partial) implements PayloadDto {
            public TextDto {
                payloadType = "TEXT";
            }

            TextDto(String text, boolean partial) {
                this("TEXT", text, partial);
            }
        }

        record ToolCallDto(String payloadType, String toolName, java.util.Map<String, String> safeArguments,
                           String resultSummary) implements PayloadDto {
            public ToolCallDto {
                payloadType = "TOOL_CALL";
            }

            ToolCallDto(String toolName, java.util.Map<String, String> safeArguments, String resultSummary) {
                this("TOOL_CALL", toolName, safeArguments, resultSummary);
            }
        }

        record ExpertHandoffDto(String payloadType, String domain, String direction, String findingStatus)
                implements PayloadDto {
            public ExpertHandoffDto {
                payloadType = "EXPERT_HANDOFF";
            }

            ExpertHandoffDto(String domain, String direction, String findingStatus) {
                this("EXPERT_HANDOFF", domain, direction, findingStatus);
            }
        }

        record SqlDto(String payloadType, String safeSql, List<String> parameterNames, String validationStatus)
                implements PayloadDto {
            public SqlDto {
                payloadType = "SQL";
            }

            SqlDto(String safeSql, List<String> parameterNames, String validationStatus) {
                this("SQL", safeSql, parameterNames, validationStatus);
            }
        }

        record ChartDto(String payloadType, String type, String title, String xField, List<String> yFields,
                        String seriesField, String unit) implements PayloadDto {
            public ChartDto {
                payloadType = "CHART";
            }

            ChartDto(String type, String title, String xField, List<String> yFields,
                     String seriesField, String unit) {
                this("CHART", type, title, xField, yFields, seriesField, unit);
            }
        }

        record TimeRangeDto(String payloadType, String status, String fromInclusive, String toExclusive,
                            String source, String explanation, boolean empty) implements PayloadDto {
            public TimeRangeDto {
                payloadType = "TIME_RANGE";
            }

            public TimeRangeDto(String status, String fromInclusive, String toExclusive,
                                String source, String explanation, boolean empty) {
                this("TIME_RANGE", status, fromInclusive, toExclusive, source, explanation, empty);
            }
        }

        record AudioDto(String payloadType, String state, Integer durationMs) implements PayloadDto {
            public AudioDto {
                payloadType = "AUDIO";
            }

            AudioDto(String state, Integer durationMs) {
                this("AUDIO", state, durationMs);
            }
        }

        record ErrorDto(String payloadType, String stage, String errorCode, boolean retryable, String safeMessage)
                implements PayloadDto {
            public ErrorDto {
                payloadType = "ERROR";
            }

            ErrorDto(String stage, String errorCode, boolean retryable, String safeMessage) {
                this("ERROR", stage, errorCode, retryable, safeMessage);
            }
        }
    }
}
