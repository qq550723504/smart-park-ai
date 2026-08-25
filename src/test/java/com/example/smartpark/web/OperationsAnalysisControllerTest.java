package com.example.smartpark.web;

import com.example.smartpark.analytics.MetricSelection;
import com.example.smartpark.analytics.OperationsAnalysisService;
import com.example.smartpark.analytics.AnalysisRunStore.RunRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationsAnalysisController.class)
@Import(ApiExceptionHandler.class)
@TestPropertySource(properties = "smartpark.analytics.enabled=true")
class OperationsAnalysisControllerTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-00000000bb01");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsAnalysisService service;

    @Test
    void startReturnsAcceptedWithRunIdAndStatusUrl() throws Exception {
        when(service.start("上周能耗")).thenReturn(record("RUNNING", List.of()));

        mockMvc.perform(post("/api/operations-analysis/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"上周能耗\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value("/api/operations-analysis/runs/" + RUN_ID));
    }

    @Test
    void blankOrOversizedQuestionIsRejected() throws Exception {
        when(service.start(any())).thenThrow(new IllegalArgumentException("分析问题不能为空"));
        mockMvc.perform(post("/api/operations-analysis/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clarificationResumesTheRun() throws Exception {
        var selection = new MetricSelection("告警", "alert_count");
        when(service.submitClarification(eq(RUN_ID), eq(List.of(selection))))
                .thenReturn(record("COMPLETED", List.of()));

        mockMvc.perform(post("/api/operations-analysis/runs/{runId}/clarifications", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selections\":[{\"term\":\"告警\",\"metric\":\"alert_count\"}]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void unknownRunIsNotFoundForBothEndpoints() throws Exception {
        when(service.get(RUN_ID)).thenThrow(new NoSuchElementException("Unknown analysis run: " + RUN_ID));
        doThrow(new NoSuchElementException("Unknown analysis run: " + RUN_ID))
                .when(service).submitClarification(eq(RUN_ID), any());

        mockMvc.perform(get("/api/operations-analysis/runs/{runId}", RUN_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/operations-analysis/runs/{runId}/clarifications", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selections\":[{\"term\":\"t\",\"metric\":\"alert_count\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void clarifyingATerminalRunConflicts() throws Exception {
        doThrow(new IllegalStateException("该运行不处于待澄清状态"))
                .when(service).submitClarification(eq(RUN_ID), any());

        mockMvc.perform(post("/api/operations-analysis/runs/{runId}/clarifications", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selections\":[{\"term\":\"t\",\"metric\":\"alert_count\"}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void failedRunExposesFailureStageWithoutSensitiveDetail() throws Exception {
        when(service.get(RUN_ID)).thenReturn(new RunRecord(RUN_ID, "上周能耗", "FAILED", List.of(), List.of(), "",
                0, false, 5, "validateSqlAst", Instant.parse("2026-08-24T00:00:00Z"), List.of(), List.of()));

        mockMvc.perform(get("/api/operations-analysis/runs/{runId}", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureStage").value("validateSqlAst"));
    }

    private static RunRecord record(String status, List<String> questions) {
        return new RunRecord(RUN_ID, "上周能耗", status, questions, List.of(), "共 3 行结果。",
                3, false, 1500, null, Instant.parse("2026-08-24T00:00:00Z"),
                List.of("building_id", "total_kwh"),
                List.of(List.of("B1", "1820.5"), List.of("B2", "1444.25"), List.of("B3", "990")));
    }
}
