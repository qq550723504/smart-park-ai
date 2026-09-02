package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** REST boundary for the bounded, session-level operations daily report. */
@RestController
@ConditionalOnProperty(name = "smartpark.analytics.enabled", havingValue = "true", matchIfMissing = false)
public class OperationsDailyReportController {

    private final OperationsDailyReportService service;

    public OperationsDailyReportController(OperationsDailyReportService service) {
        this.service = service;
    }

    @PostMapping("/api/operations-reports/runs")
    public ResponseEntity<Map<String, String>> start(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @RequestBody(required = false) Map<String, Object> body) {
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        if (body != null && !body.isEmpty()) {
            throw new IllegalArgumentException("report request must be an empty JSON object");
        }
        var report = service.start();
        return ResponseEntity.accepted().body(Map.of(
                "runId", report.runId().toString(),
                "statusUrl", "/api/operations-reports/runs/" + report.runId()));
    }

    @GetMapping("/api/operations-reports/runs/{runId}")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @PathVariable UUID runId) {
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        return OperationsDailyReportDtos.from(service.get(runId));
    }
}
