package com.example.smartpark.web;

import com.example.smartpark.orchestration.OrchestrationDefinition;
import com.example.smartpark.orchestration.OrchestrationInput;
import com.example.smartpark.orchestration.OrchestrationRun;
import com.example.smartpark.orchestration.OrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orchestrations/runs")
public class OrchestrationController {
    private final OrchestrationService service;

    public OrchestrationController(OrchestrationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Demo-Role") String role,
            @RequestHeader(value = "X-Demo-Actor", required = false) String actor,
            @RequestBody StartRequest request) {
        DemoRole.require(role, DemoRole.VIEWER, DemoRole.OPERATOR, DemoRole.APPROVER, DemoRole.ADMIN);
        String definitionId = request.definitionId() == null || request.definitionId().isBlank()
                ? OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT : request.definitionId();
        var started = service.start(definitionId, request.input(), idempotencyKey, actor, DemoRole.parse(role).name());
        OrchestrationRun run = started.run();
        return ResponseEntity.accepted().body(Map.of(
                "runId", run.id().toString(),
                "status", run.status().name(),
                "statusUrl", "/api/orchestrations/runs/" + run.id(),
                "traceUrl", "/api/executions/" + run.traceId() + "/events?role=" + run.role(),
                "idempotentReplay", !started.created()));
    }

    @GetMapping("/{runId}")
    public OrchestrationDtos.RunResponse get(
            @PathVariable UUID runId,
            @RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.VIEWER, DemoRole.OPERATOR, DemoRole.APPROVER, DemoRole.ADMIN);
        DemoRole requester = DemoRole.parse(role);
        requireRunAccess(service.snapshot(runId), requester);
        OrchestrationRun run = service.get(runId);
        return OrchestrationDtos.RunResponse.from(run);
    }

    @PostMapping("/{runId}/cancel")
    public OrchestrationDtos.RunResponse cancel(
            @PathVariable UUID runId,
            @RequestHeader("X-Demo-Role") String role) {
        DemoRole.require(role, DemoRole.VIEWER, DemoRole.OPERATOR, DemoRole.APPROVER, DemoRole.ADMIN);
        DemoRole requester = DemoRole.parse(role);
        requireRunAccess(service.snapshot(runId), requester);
        return OrchestrationDtos.RunResponse.from(service.cancel(runId));
    }

    private static void requireRunAccess(OrchestrationRun run, DemoRole requester) {
        if (requester != DemoRole.ADMIN && !requester.name().equals(run.role())) {
            throw new ForbiddenOperationException("Demo role cannot access another role's orchestration run");
        }
    }

    public record StartRequest(String definitionId, OrchestrationInput input) {
        public StartRequest {
            if (input == null) throw new IllegalArgumentException("input is required");
        }
    }
}
