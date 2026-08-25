package com.example.smartpark.web;

import com.example.smartpark.analytics.AnalysisRunStore.RunRecord;
import com.example.smartpark.analytics.MetricSelection;
import com.example.smartpark.analytics.OperationsAnalysisService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST boundary of natural-language operations analysis. Event streaming reuses
 * the unified GET /api/executions/{runId}/events SSE endpoint.
 */
@RestController
@ConditionalOnProperty(name = "smartpark.analytics.enabled", havingValue = "true", matchIfMissing = false)
public class OperationsAnalysisController {

    private final OperationsAnalysisService service;

    public OperationsAnalysisController(OperationsAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/api/operations-analysis/runs")
    public ResponseEntity<Map<String, String>> start(@RequestBody Map<String, String> body) {
        String question = body == null ? null : body.get("question");
        RunRecord record = service.start(question);
        return ResponseEntity.accepted().body(Map.of(
                "runId", record.runId().toString(),
                "statusUrl", "/api/operations-analysis/runs/" + record.runId()));
    }

    @PostMapping("/api/operations-analysis/runs/{runId}/clarifications")
    public ResponseEntity<Map<String, Object>> clarify(@PathVariable UUID runId,
                                                       @RequestBody ClarificationRequest request) {
        List<MetricSelection> selections = request.selections() == null ? List.of() : request.selections();
        RunRecord record = service.submitClarification(runId, selections);
        return ResponseEntity.accepted().body(OperationsAnalysisDtos.from(record));
    }

    @GetMapping("/api/operations-analysis/runs/{runId}")
    public Map<String, Object> status(@PathVariable UUID runId) {
        return OperationsAnalysisDtos.from(service.get(runId));
    }

    public record ClarificationRequest(List<MetricSelection> selections) {}
}
