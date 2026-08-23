package com.example.smartpark.model.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Diagnosis(
        String id,
        String alertId,
        String deviceId,
        RiskLevel riskLevel,
        String rootCause,
        String summary,
        List<String> evidence,
        String recommendedAction,
        double confidence,
        Instant diagnosedAt) {

    public Diagnosis {
        id = Objects.requireNonNull(id, "id");
        alertId = Objects.requireNonNull(alertId, "alertId");
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        rootCause = Objects.requireNonNull(rootCause, "rootCause");
        summary = Objects.requireNonNull(summary, "summary");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        recommendedAction = Objects.requireNonNull(recommendedAction, "recommendedAction");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        diagnosedAt = Objects.requireNonNull(diagnosedAt, "diagnosedAt");
    }

    /**
     * 兼容从早期无置信度检查点恢复的诊断数据。
     * 缺失置信度时按零处理，以保守方式强制进入人工审批。
     */
    public Diagnosis(
            String id,
            String alertId,
            String deviceId,
            RiskLevel riskLevel,
            String rootCause,
            String summary,
            List<String> evidence,
            String recommendedAction,
            Instant diagnosedAt) {
        this(
                id,
                alertId,
                deviceId,
                riskLevel,
                rootCause,
                summary,
                evidence,
                recommendedAction,
                0.0,
                diagnosedAt);
    }
}
