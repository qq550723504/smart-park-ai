package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReport;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe public projection of a session-level operations report. */
final class OperationsDailyReportDtos {

    private OperationsDailyReportDtos() {
    }

    static Map<String, Object> from(OperationsDailyReport report) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("runId", report.runId().toString());
        dto.put("status", report.status());
        dto.put("createdAt", report.createdAt().toString());
        dto.put("updatedAt", report.updatedAt().toString());
        dto.put("sections", report.sections().stream().map(OperationsDailyReportDtos::section).toList());
        return dto;
    }

    private static Map<String, Object> section(OperationsDailyReport.SectionResult result) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", result.id());
        dto.put("title", result.title());
        dto.put("question", result.question());
        dto.put("status", result.status().name());
        if (!result.summary().isBlank()) dto.put("summary", result.summary());
        if (result.status() == com.example.smartpark.analytics.report.OperationsReportSectionStatus.COMPLETED) {
            dto.put("rowCount", result.rowCount());
            dto.put("truncated", result.truncated());
            if (!result.columns().isEmpty()) {
                dto.put("columns", result.columns());
                dto.put("rows", result.rows());
            }
        }
        if (!result.timeResolution().isEmpty()) dto.put("timeResolution", result.timeResolution());
        if (result.failureStage() != null) dto.put("failureStage", result.failureStage());
        return dto;
    }
}
