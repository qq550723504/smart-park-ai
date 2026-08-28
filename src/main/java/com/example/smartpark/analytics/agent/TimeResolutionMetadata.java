package com.example.smartpark.analytics.agent;

import com.example.smartpark.execution.model.DisplayPayload;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * User-facing projection of how the analysis window was determined.
 * Only safe fields travel here: no raw model output, no parser internals,
 * no sidecar details. Rendered verbatim in REST payloads and SSE cards.
 *
 * @param status       NONE / PARSED / EMPTY（与解析链状态一一对应）
 * @param fromInclusive 实际使用的区间起点（NONE 时为 null）
 * @param toExclusive   实际使用的区间终点（半开；EMPTY 时等于起点）
 * @param source       EXPLICIT_USER_RANGE 或 DEFAULT_METRIC_LOOKBACK
 * @param explanation  面向用户的短说明，例如“未指定时间范围，本次按指标默认回看期分析”
 * @param empty        周期零宽标记：true 表示当前周期刚开始、暂无数据
 */
public record TimeResolutionMetadata(
        String status,
        Instant fromInclusive,
        Instant toExclusive,
        String source,
        String explanation,
        boolean empty) {

    public static final String SOURCE_EXPLICIT = "EXPLICIT_USER_RANGE";
    public static final String SOURCE_DEFAULT_LOOKBACK = "DEFAULT_METRIC_LOOKBACK";

    private static final Set<String> STATUSES = Set.of("NONE", "PARSED", "EMPTY");
    private static final Set<String> SOURCES = Set.of(SOURCE_EXPLICIT, SOURCE_DEFAULT_LOOKBACK);

    public TimeResolutionMetadata {
        Objects.requireNonNull(status, "status");
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported time resolution status: " + status);
        }
        Objects.requireNonNull(source, "source");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("unsupported time resolution source: " + source);
        }
        explanation = explanation == null ? "" : explanation;
        if ("EMPTY".equals(status)) {
            Objects.requireNonNull(fromInclusive, "empty range still carries its boundary instant");
            if (!fromInclusive.equals(toExclusive)) {
                throw new IllegalArgumentException("EMPTY metadata requires equal boundaries");
            }
        } else if (fromInclusive != null) {
            Objects.requireNonNull(toExclusive, "toExclusive is required when fromInclusive is present");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("non-empty metadata requires ordered boundaries");
            }
        }
    }

    /** 默认回看期投影；区间由调用方在指标确定后填充。 */
    public static TimeResolutionMetadata defaultLookback(Instant from, Instant to) {
        return new TimeResolutionMetadata("NONE", from, to,
                SOURCE_DEFAULT_LOOKBACK, "未指定时间范围，本次按指标默认回看期分析", false);
    }

    /** 显式时间范围投影；mentions 为原文逐字片段。 */
    public static TimeResolutionMetadata explicit(Instant from, Instant to, String mentionsText) {
        return new TimeResolutionMetadata("PARSED", from, to,
                SOURCE_EXPLICIT, "已按您指定的时间范围「" + mentionsText + "」查询", false);
    }

    /** 当前周期零宽投影。 */
    public static TimeResolutionMetadata emptyPeriod(Instant boundary) {
        return new TimeResolutionMetadata("EMPTY", boundary, boundary,
                SOURCE_EXPLICIT, "当前周期刚开始，暂无数据", true);
    }

    /** 转换为随 SSE 理解完成事件下发的安全展示负载。 */
    public DisplayPayload.TimeRangePayload toDisplayPayload() {
        return new DisplayPayload.TimeRangePayload(status,
                fromInclusive == null ? null : fromInclusive.toString(),
                toExclusive == null ? null : toExclusive.toString(),
                source, explanation, empty);
    }
}
