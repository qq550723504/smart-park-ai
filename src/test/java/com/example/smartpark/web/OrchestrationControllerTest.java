package com.example.smartpark.web;

import com.example.smartpark.orchestration.OrchestrationDefinition;
import com.example.smartpark.orchestration.OrchestrationInput;
import com.example.smartpark.orchestration.OrchestrationRun;
import com.example.smartpark.orchestration.OrchestrationRunStore;
import com.example.smartpark.orchestration.OrchestrationService;
import com.example.smartpark.orchestration.OrchestrationStatus;
import com.example.smartpark.orchestration.OrchestrationStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrchestrationController.class)
@Import(ApiExceptionHandler.class)
class OrchestrationControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OrchestrationService service;

    @Test
    void startsWithRequiredRoleAndIdempotencyKeyWithoutExposingFingerprint() throws Exception {
        OrchestrationRun run = run("OPERATOR", OrchestrationStatus.RUNNING);
        when(service.start(org.mockito.ArgumentMatchers.eq(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("request-1"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("OPERATOR")))
                .thenReturn(new OrchestrationRunStore.StartResult(run, true));

        mockMvc.perform(post("/api/orchestrations/runs")
                        .header("X-Demo-Role", "OPERATOR")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definitionId":"JOINT_ANOMALY_ASSESSMENT","input":{
                                  "question":"检查园区异常","alertId":null,"buildingIds":[],
                                  "energyRelated":false,"crossDomain":false,
                                  "securityRelated":false,"requestAction":false}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(run.id().toString()))
                .andExpect(jsonPath("$.traceUrl").value(
                        "/api/executions/" + run.id() + "/events?role=OPERATOR"))
                .andExpect(jsonPath("$.requestFingerprint").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void rejectsCustomerAgentAndMissingIdempotencyKey() throws Exception {
        String body = """
                {"input":{"question":"检查园区异常","alertId":null,"buildingIds":[],
                "energyRelated":false,"crossDomain":false,"securityRelated":false,"requestAction":false}}
                """;
        mockMvc.perform(post("/api/orchestrations/runs")
                        .header("X-Demo-Role", "CUSTOMER_AGENT")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orchestrations/runs")
                        .header("X-Demo-Role", "OPERATOR")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preventsCrossRoleReadButAllowsAdminAndOwnerRole() throws Exception {
        OrchestrationRun run = run("OPERATOR", OrchestrationStatus.COMPLETED);
        when(service.snapshot(run.id())).thenReturn(run);
        when(service.get(run.id())).thenReturn(run);

        mockMvc.perform(get("/api/orchestrations/runs/" + run.id()).header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orchestrations/runs/" + run.id()).header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(get("/api/orchestrations/runs/" + run.id()).header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsCrossRoleAccessBeforeApprovalReconciliationOrCancellation() throws Exception {
        OrchestrationRun run = run("APPROVER", OrchestrationStatus.WAITING_APPROVAL);
        when(service.snapshot(run.id())).thenReturn(run);

        mockMvc.perform(get("/api/orchestrations/runs/" + run.id()).header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orchestrations/runs/" + run.id() + "/cancel")
                        .header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());

        verify(service, never()).get(run.id());
        verify(service, never()).cancel(run.id());
    }

    @Test
    void approvalMaintenanceIsAdminOnly() throws Exception {
        when(service.reconcileWaitingApprovals()).thenReturn(2);

        mockMvc.perform(post("/api/orchestrations/runs/maintenance/reconcile-approvals")
                        .header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isForbidden());
        verify(service, never()).reconcileWaitingApprovals();

        mockMvc.perform(post("/api/orchestrations/runs/maintenance/reconcile-approvals")
                        .header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiredRuns").value(2));
        verify(service).reconcileWaitingApprovals();
    }

    private static OrchestrationRun run(String role, OrchestrationStatus status) {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        UUID id = UUID.randomUUID();
        OrchestrationInput input = new OrchestrationInput("检查园区异常", null, List.of(),
                false, false, false, false);
        return new OrchestrationRun(id, OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                status, now, now, status.isTerminal() ? now : null, "demo", role, input, "安全摘要",
                OrchestrationDefinition.steps(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT).stream()
                        .map(OrchestrationStep::pending).toList(),
                List.of(), id, null, null, "private-key", "private-fingerprint", false, 1, List.of());
    }
}
