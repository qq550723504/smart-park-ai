package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReport;
import com.example.smartpark.analytics.report.OperationsDailyReportService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Safe public projections. Artifact content and internal persistence paths are never exposed. */
final class OperationsDailyReportDtos {
    private OperationsDailyReportDtos() { }

    static Map<String, Object> detail(OperationsDailyReport report) {
        Map<String, Object> dto = summary(report);
        dto.put("requestedBy", report.requestedBy());
        dto.put("role", report.role());
        dto.put("startedAt", report.startedAt());
        dto.put("completedAt", report.completedAt());
        dto.put("asOf", report.asOf());
        dto.put("summary", report.summary());
        dto.put("sections", report.sections().stream().map(OperationsDailyReportDtos::section).toList());
        dto.put("evidence", report.evidence());
        dto.put("sourceReferences", report.sourceReferences());
        dto.put("schemaVersion", report.schemaVersion());
        dto.put("generationVersion", report.generationVersion());
        return dto;
    }

    static Map<String, Object> page(OperationsDailyReportService.Page page) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("content", page.content().stream().map(OperationsDailyReportDtos::summary).toList());
        dto.put("page", page.page());
        dto.put("size", page.size());
        dto.put("totalElements", page.totalElements());
        dto.put("hasNext", page.hasNext());
        return dto;
    }

    private static Map<String, Object> summary(OperationsDailyReport report) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("reportId", report.reportId());
        dto.put("reportType", report.reportType());
        dto.put("title", report.title());
        dto.put("status", report.status());
        dto.put("createdAt", report.createdAt());
        dto.put("completedAt", report.completedAt());
        dto.put("timeWindow", report.timeWindow());
        dto.put("timezone", report.timezone());
        dto.put("asOf", report.asOf());
        dto.put("runId", report.runId());
        dto.put("traceId", report.traceId());
        dto.put("downloadAvailable", report.artifact() != null);
        if (report.artifact() != null) dto.put("artifact", artifact(report.artifact()));
        return dto;
    }

    private static Map<String, Object> section(OperationsDailyReport.SectionResult section) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sectionId", section.sectionId());
        dto.put("title", section.title());
        dto.put("question", section.question());
        dto.put("status", section.status());
        dto.put("summary", section.summary());
        dto.put("rowCount", section.rowCount());
        dto.put("truncated", section.truncated());
        dto.put("columns", section.columns());
        dto.put("rows", section.rows());
        dto.put("timeResolution", section.timeResolution());
        dto.put("evidenceReferences", section.evidenceReferences());
        dto.put("sourceReferences", section.sourceReferences());
        dto.put("partialReason", section.partialReason());
        dto.put("failureReason", section.failureReason());
        dto.put("runId", section.runId());
        return dto;
    }

    private static Map<String, Object> artifact(OperationsDailyReport.Artifact artifact) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("artifactId", artifact.artifactId());
        dto.put("format", artifact.format());
        dto.put("fileName", artifact.fileName());
        dto.put("contentType", artifact.contentType());
        dto.put("size", artifact.size());
        dto.put("createdAt", artifact.createdAt());
        dto.put("checksum", artifact.checksum());
        dto.put("rendererVersion", artifact.rendererVersion());
        return dto;
    }
}
