package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReportService;
import com.example.smartpark.analytics.report.OperationsReportRequest;
import com.example.smartpark.analytics.report.OperationsReportStatus;
import com.example.smartpark.audit.AuditTrail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** REST boundary for durable immutable operations report snapshots. */
@RestController
public class OperationsDailyReportController {
    private static final Set<String> REQUEST_FIELDS = Set.of("reportType", "timeWindow", "timezone");
    private static final Set<String> WINDOW_FIELDS = Set.of("fromInclusive", "toExclusive");
    private final OperationsDailyReportService service;
    private final AuditTrail auditTrail;

    public OperationsDailyReportController(OperationsDailyReportService service,
                                           ObjectProvider<AuditTrail> auditTrail) {
        this.service = service;
        this.auditTrail = auditTrail.getIfAvailable(AuditTrail::new);
    }

    @PostMapping("/api/operations-reports")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        DemoRole actual = DemoRole.parse(role);
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        OperationsReportRequest request = request(body);
        var result = service.start(request, idempotencyKey, "demo-role:" + actual.name(), actual.name());
        var report = result.report();
        auditTrail.record(actual.name(), "OPERATIONS_REPORT_CREATE", report.reportId().toString(),
                result.created() ? "ACCEPTED" : "IDEMPOTENT_REPLAY");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", report.reportId());
        response.put("runId", report.runId());
        response.put("status", report.status());
        response.put("statusUrl", "/api/operations-reports/" + report.reportId());
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/api/operations-reports")
    public Map<String, Object> list(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @RequestParam(required = false) String reportType,
            @RequestParam(required = false) OperationsReportStatus status,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        DemoRole actual = DemoRole.parse(role);
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        Map<String, Object> result = OperationsDailyReportDtos.page(service.list(actual.name(), reportType, status,
                createdFrom, createdTo, page, size));
        auditTrail.record(actual.name(), "OPERATIONS_REPORT_LIST", "operations-reports", "SUCCEEDED");
        return result;
    }

    @GetMapping("/api/operations-reports/{reportId}")
    public Map<String, Object> detail(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @PathVariable UUID reportId) {
        DemoRole actual = DemoRole.parse(role);
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        Map<String, Object> result = OperationsDailyReportDtos.detail(service.get(reportId, actual.name()));
        auditTrail.record(actual.name(), "OPERATIONS_REPORT_READ", reportId.toString(), "SUCCEEDED");
        return result;
    }

    @GetMapping("/api/operations-reports/{reportId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @PathVariable UUID reportId) {
        DemoRole actual = DemoRole.parse(role);
        DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN);
        var artifact = service.download(reportId, actual.name());
        auditTrail.record(actual.name(), "OPERATIONS_REPORT_DOWNLOAD", reportId.toString(), "SUCCEEDED");
        byte[] content = artifact.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Checksum-SHA256", artifact.checksum())
                .body(content);
    }

    private OperationsReportRequest request(Map<String, Object> body) {
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("report request is required");
        if (!REQUEST_FIELDS.containsAll(body.keySet())) throw new IllegalArgumentException("unsupported report field");
        if (!body.containsKey("reportType") || !body.containsKey("timeWindow")) {
            throw new IllegalArgumentException("reportType and timeWindow are required");
        }
        String reportType = text(body.get("reportType"), "reportType");
        String timezone = text(body.getOrDefault("timezone", OperationsReportRequest.DEFAULT_TIMEZONE), "timezone");
        if (!(body.get("timeWindow") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("invalid timeWindow");
        if (!WINDOW_FIELDS.containsAll(raw.keySet().stream().map(String::valueOf).toList())) {
            throw new IllegalArgumentException("unsupported timeWindow field");
        }
        if (!raw.containsKey("fromInclusive") || !raw.containsKey("toExclusive")) {
            throw new IllegalArgumentException("incomplete timeWindow");
        }
        OperationsReportRequest.TimeWindow window = new OperationsReportRequest.TimeWindow(
                Instant.parse(text(raw.get("fromInclusive"), "fromInclusive")),
                Instant.parse(text(raw.get("toExclusive"), "toExclusive")));
        return new OperationsReportRequest(reportType, window, timezone);
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 100) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text.trim();
    }
}
