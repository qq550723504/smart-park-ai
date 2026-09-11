package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReport;
import com.example.smartpark.analytics.report.OperationsDailyReportService;
import com.example.smartpark.analytics.report.OperationsDailyReportStore;
import com.example.smartpark.analytics.report.OperationsReportRequest;
import com.example.smartpark.analytics.report.OperationsReportStatus;
import com.example.smartpark.analytics.report.OperationsReportUnavailableException;
import com.example.smartpark.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationsDailyReportController.class)
@Import(ApiExceptionHandler.class)
@TestPropertySource(properties = "smartpark.analytics.enabled=true")
class OperationsDailyReportControllerTest {
    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");
    @Autowired MockMvc mockMvc;
    @MockitoBean OperationsDailyReportService service;
    @MockitoBean AuditTrail auditTrail;

    @Test
    void createsListsReadsAndDownloadsSnapshots() throws Exception {
        OperationsReportRequest request = request();
        OperationsDailyReport report = report();
        when(service.start(eq(request), eq("key-1"), eq("demo-role:OPERATOR"), eq("OPERATOR")))
                .thenReturn(new OperationsDailyReportStore.StartResult(report, true));
        when(service.list(eq("OPERATOR"), any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new OperationsDailyReportService.Page(List.of(report), 0, 20, 1, false));
        when(service.get(report.reportId(), "OPERATOR")).thenReturn(report);
        when(service.download(report.reportId(), "OPERATOR")).thenReturn(report.artifact());

        mockMvc.perform(post("/api/operations-reports")
                        .header("X-Demo-Role", "OPERATOR").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value(report.reportId().toString()))
                .andExpect(jsonPath("$.runId").value(report.runId().toString()));
        mockMvc.perform(get("/api/operations-reports").header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].downloadAvailable").value(true));
        mockMvc.perform(get("/api/operations-reports/" + report.reportId())
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.artifact.content").doesNotExist());
        mockMvc.perform(get("/api/operations-reports/" + report.reportId() + "/download")
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk()).andExpect(content().bytes("%PDF-test".getBytes()))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("safe.pdf")))
                .andExpect(header().longValue("Content-Length", 9))
                .andExpect(header().string("X-Checksum-SHA256", "abc123"));
        verify(auditTrail).record("OPERATOR", "OPERATIONS_REPORT_DOWNLOAD",
                report.reportId().toString(), "SUCCEEDED");
    }

    @Test
    void rejectsMissingKeyUnsupportedFieldsRolesAndUnsafePath() throws Exception {
        mockMvc.perform(post("/api/operations-reports").header("X-Demo-Role", "OPERATOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/operations-reports").header("X-Demo-Role", "OPERATOR")
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/operations-reports").header("X-Demo-Role", "OPERATOR")
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"C:\\\\secret\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/operations-reports").header("X-Demo-Role", "OPERATOR")
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"..\\\\secret.md\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/operations-reports").header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/operations-reports/..%2F..%2Fsecret/download")
                        .header("X-Demo-Role", "ADMIN"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rejectsMalformedTimeWindowAsClientValidationError() throws Exception {
        mockMvc.perform(post("/api/operations-reports")
                        .header("X-Demo-Role", "OPERATOR").header("Idempotency-Key", "bad-time")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reportType":"OPERATIONS_DAILY","timeWindow":{
                                  "fromInclusive":"not-an-instant","toExclusive":"2026-09-09T02:00:00Z"},
                                  "timezone":"Asia/Shanghai"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void downloadRejectsUnknownNotReadyUnauthorizedAndArtifactGuessing() throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID notReady = UUID.randomUUID();
        when(service.download(unknown, "OPERATOR"))
                .thenThrow(new java.util.NoSuchElementException("internal path must stay hidden"));
        when(service.download(notReady, "OPERATOR"))
                .thenThrow(new IllegalStateException("artifact is not ready"));

        mockMvc.perform(get("/api/operations-reports/" + unknown + "/download")
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Requested resource was not found"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal path"))));
        mockMvc.perform(get("/api/operations-reports/" + notReady + "/download")
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request conflicts with current resource state"));
        for (String role : List.of("VIEWER", "APPROVER", "CUSTOMER_AGENT")) {
            mockMvc.perform(get("/api/operations-reports/" + UUID.randomUUID() + "/download")
                            .header("X-Demo-Role", role))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/operations-reports/" + UUID.randomUUID()
                        + "/artifacts/" + UUID.randomUUID()).header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsGenerationUnavailableWithoutHidingReadApi() throws Exception {
        OperationsReportRequest request = request();
        when(service.start(eq(request), eq("disabled-key"), eq("demo-role:OPERATOR"), eq("OPERATOR")))
                .thenThrow(new OperationsReportUnavailableException("analytics credentials absent"));
        when(service.list(eq("OPERATOR"), any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new OperationsDailyReportService.Page(List.of(), 0, 20, 0, false));

        mockMvc.perform(post("/api/operations-reports")
                        .header("X-Demo-Role", "OPERATOR").header("Idempotency-Key", "disabled-key")
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Operations report generation is unavailable"));
        mockMvc.perform(get("/api/operations-reports").header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    private static OperationsReportRequest request() {
        return new OperationsReportRequest(OperationsReportRequest.DAILY,
                new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW), "Asia/Shanghai");
    }

    private static String requestJson() {
        return """
                {"reportType":"OPERATIONS_DAILY","timeWindow":{
                  "fromInclusive":"2026-09-09T01:00:00Z","toExclusive":"2026-09-09T02:00:00Z"},
                  "timezone":"Asia/Shanghai"}
                """;
    }

    private static OperationsDailyReport report() {
        UUID reportId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        byte[] content = "%PDF-test".getBytes();
        OperationsDailyReport.Artifact artifact = new OperationsDailyReport.Artifact(UUID.randomUUID(),
                "PDF", "safe.pdf", "application/pdf", content.length,
                NOW, "abc123", "pdfbox-v1", content);
        return new OperationsDailyReport(reportId, OperationsReportRequest.DAILY, "智慧园区运营日报",
                OperationsReportStatus.COMPLETED, "demo-role:OPERATOR", "OPERATOR", NOW, NOW, NOW,
                request().timeWindow(), "Asia/Shanghai", NOW, "完成", List.of(), List.of(), List.of(),
                runId, runId, OperationsDailyReport.CURRENT_SCHEMA_VERSION,
                OperationsDailyReport.CURRENT_GENERATION_VERSION, artifact, "key", "fingerprint", 1, List.of());
    }
}
