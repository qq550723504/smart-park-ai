package com.example.smartpark.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public status projection of an analysis run; never exposes SQL or connection details. */
final class OperationsAnalysisDtos {

    private OperationsAnalysisDtos() {
    }

    static Map<String, Object> from(com.example.smartpark.analytics.AnalysisRunStore.RunRecord record) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("runId", record.runId().toString());
        dto.put("status", record.status());
        if (!record.clarificationQuestions().isEmpty()) {
            dto.put("clarificationQuestions", record.clarificationQuestions());
        }
        if (record.summary() != null && !record.summary().isBlank()) {
            dto.put("summary", record.summary());
        }
        if ("COMPLETED".equals(record.status())) {
            dto.put("rowCount", record.rowCount());
            dto.put("truncated", record.truncated());
        }
        if (!record.columns().isEmpty()) {
            dto.put("columns", record.columns());
            dto.put("rows", record.rows());
        }
        if (record.failureStage() != null) {
            dto.put("failureStage", record.failureStage());
        }
        dto.put("createdAt", record.createdAt().toString());
        return dto;
    }
}
