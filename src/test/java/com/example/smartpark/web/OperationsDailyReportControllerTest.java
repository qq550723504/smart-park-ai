package com.example.smartpark.web;

import com.example.smartpark.analytics.report.OperationsDailyReport;
import com.example.smartpark.analytics.report.OperationsDailyReportService;
import com.example.smartpark.analytics.report.OperationsDailyReportDefinition;
import com.example.smartpark.analytics.report.OperationsReportSectionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationsDailyReportController.class)
@Import(ApiExceptionHandler.class)
@TestPropertySource(properties = "smartpark.analytics.enabled=true")
class OperationsDailyReportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OperationsDailyReportService service;

    @Test
    void startsOnlyForOperatorAndAdminWithEmptyBody() throws Exception {
        UUID runId = UUID.randomUUID();
        OperationsDailyReport report = report(runId, "RUNNING");
        when(service.start()).thenReturn(report);

        mockMvc.perform(post("/api/operations-reports/runs")
                        .header("X-Demo-Role", "OPERATOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.statusUrl").value("/api/operations-reports/runs/" + runId));

        mockMvc.perform(post("/api/operations-reports/runs")
                        .header("X-Demo-Role", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        verify(service).start();
    }

    @Test
    void rejectsUnknownRequestFieldsAndReturnsSafeStatus() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.get(runId)).thenReturn(report(runId, "COMPLETED"));

        mockMvc.perform(post("/api/operations-reports/runs")
                        .header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"secret\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/operations-reports/runs/" + runId)
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sections[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.sections[0].rows[0][0]").value("safe"));
    }

    private static OperationsDailyReport report(UUID runId, String status) {
        var section = new OperationsDailyReport.SectionResult(
                OperationsDailyReportDefinition.sections().get(0).id(),
                OperationsDailyReportDefinition.sections().get(0).title(),
                OperationsDailyReportDefinition.sections().get(0).question(),
                OperationsReportSectionStatus.COMPLETED,
                "安全摘要", 1, false, List.of("value"), List.of(List.of("safe")),
                Map.of("status", "RESOLVED"), null);
        return new OperationsDailyReport(runId, status, Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-02T00:01:00Z"), List.of(section));
    }
}
