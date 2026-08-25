package com.example.smartpark.web;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AlertWorkflowController.class, ApprovalController.class})
@Import(ApiExceptionHandler.class)
class AlertWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertWorkflow workflow;

    @MockitoBean
    private AlertPort alertPort;

    @Test
    void startingKnownAlertReturnsSafeWorkflowResponse() throws Exception {
        when(alertPort.getAlert("ALT-TEMP-001")).thenReturn(alert("ALT-TEMP-001"));
        when(workflow.start("ALT-TEMP-001")).thenReturn(snapshot(
                "wf-1", "ALT-TEMP-001", WorkflowStatus.COMPLETED, Optional.empty()));

        mockMvc.perform(post("/api/alerts/ALT-TEMP-001/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.alertId").value("ALT-TEMP-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.eventSequence").value(7))
                .andExpect(jsonPath("$.statePayload").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret prompt"))));
    }

    @Test
    void startingUnknownAlertReturnsNotFoundWithoutCreatingAWorkflow() throws Exception {
        when(alertPort.getAlert("ALT-MISSING"))
                .thenThrow(new IllegalArgumentException("Unknown alert: ALT-MISSING"));

        mockMvc.perform(post("/api/alerts/ALT-MISSING/workflows"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        verify(workflow, never()).start(any());
    }

    @Test
    void statusOfUnknownWorkflowReturnsNotFound() throws Exception {
        when(workflow.status("wf-missing"))
                .thenThrow(new NoSuchElementException("Unknown workflow: wf-missing"));

        mockMvc.perform(get("/api/workflows/wf-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void approvalRequiresAndPassesTheIdempotencyKey() throws Exception {
        when(workflow.approve(any(), any())).thenReturn(snapshot(
                "wf-approval", "ALT-POWER-001", WorkflowStatus.COMPLETED, Optional.empty()));

        mockMvc.perform(post("/api/workflows/wf-approval/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision":"APPROVE",
                                  "reviewer":"operator-1",
                                  "comment":"safe to dispatch",
                                  "idempotencyKey":"approval-request-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-approval"));

        ArgumentCaptor<ApprovalDecision> decision = ArgumentCaptor.forClass(ApprovalDecision.class);
        verify(workflow).approve(org.mockito.ArgumentMatchers.eq("wf-approval"), decision.capture());
        assertThat(decision.getValue().decision()).isEqualTo(ApprovalDecision.Decision.APPROVED);
        assertThat(decision.getValue().reviewer()).isEqualTo("operator-1");
        assertThat(decision.getValue().comment()).isEqualTo("safe to dispatch");
        assertThat(decision.getValue().idempotencyKey()).isEqualTo("approval-request-1");
        assertThat(decision.getValue().decidedAt()).isNotNull();
    }

    @Test
    void approvalValidationRejectsBlankFieldsAndMalformedDecision() throws Exception {
        mockMvc.perform(post("/api/workflows/wf-1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision":"APPROVE",
                                  "reviewer":" ",
                                  "comment":"ok",
                                  "idempotencyKey":" "
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/workflows/wf-1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision":"OVERRIDE",
                                  "reviewer":"operator-1",
                                  "comment":"ok",
                                  "idempotencyKey":"approval-request-2"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(workflow, never()).approve(any(), any());
    }

    @Test
    void unknownAndInvalidApprovalStatesMapToNotFoundAndConflict() throws Exception {
        when(workflow.approve(org.mockito.ArgumentMatchers.eq("wf-missing"), any()))
                .thenThrow(new NoSuchElementException("Unknown workflow: wf-missing"));
        when(workflow.approve(org.mockito.ArgumentMatchers.eq("wf-complete"), any()))
                .thenThrow(new IllegalStateException("Workflow must be WAITING_APPROVAL"));
        when(workflow.approve(org.mockito.ArgumentMatchers.eq("wf-conflict"), any()))
                .thenThrow(new IllegalArgumentException("idempotencyKey was already used"));

        String body = """
                {
                  "decision":"REJECT",
                  "reviewer":"operator-1",
                  "comment":"insufficient evidence",
                  "idempotencyKey":"approval-request-3"
                }
                """;

        mockMvc.perform(post("/api/workflows/wf-missing/approval")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workflows/wf-complete/approval")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/workflows/wf-conflict/approval")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    private static Alert alert(String alertId) {
        return new Alert(
                alertId,
                "PARK-A",
                "A1",
                "DEV-HVAC-001",
                AlertClassification.TEMPERATURE,
                RiskLevel.LOW,
                "temperature alert",
                Instant.parse("2026-08-23T00:15:00Z"),
                List.of("sensor reading"));
    }

    private static WorkflowSnapshot snapshot(
            String workflowId,
            String alertId,
            WorkflowStatus status,
            Optional<ApprovalDecision> approval) {
        return new WorkflowSnapshot(
                workflowId,
                alertId,
                status,
                Map.of("rawPrompt", "secret prompt"),
                null,
                approval,
                null,
                List.of(),
                7);
    }
}

@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=true",
        "spring.ai.dashscope.api-key=test-key"
})
class WorkflowRuntimeControllerTest {

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private AlertWorkflowController alertWorkflowController;

    @Autowired
    private ApprovalController approvalController;

    @Autowired
    private WorkflowEventController workflowEventController;

    @Autowired
    private AlertWorkflow workflow;

    @Autowired
    private AlertPort alertPort;

    @Autowired
    private WorkflowEventPublisher eventPublisher;

    @Test
    void runtimeContextWiresTheWorkflowAndAllHttpBoundaries() {
        assertThat(alertWorkflowController).isNotNull();
        assertThat(approvalController).isNotNull();
        assertThat(workflowEventController).isNotNull();
        assertThat(workflow).isNotNull();
        assertThat(alertPort).isNotNull();
        assertThat(eventPublisher).isNotNull();
    }
}
