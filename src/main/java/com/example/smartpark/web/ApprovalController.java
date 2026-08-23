package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.workflow.AlertWorkflow;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api/workflows")
@Validated
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalController {

    private final AlertWorkflow workflow;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public ApprovalController(AlertWorkflow workflow) {
        this(workflow, Clock.systemUTC(), new AuditTrail());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalController(AlertWorkflow workflow, ObjectProvider<AuditTrail> auditTrail) {
        this(workflow, Clock.systemUTC(), auditTrail.getIfAvailable(AuditTrail::new));
    }

    ApprovalController(AlertWorkflow workflow, Clock clock) {
        this(workflow, clock, new AuditTrail());
    }

    ApprovalController(AlertWorkflow workflow, Clock clock, AuditTrail auditTrail) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @PostMapping("/{workflowId}/approval")
    public WebDtos.WorkflowResponse approve(
            @PathVariable String workflowId,
            @Valid @RequestBody WebDtos.ApprovalRequest request,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.requireIfPresent(role, DemoRole.APPROVER, DemoRole.ADMIN);
        ApprovalDecision decision = request.toDomain(Instant.now(clock));
        WebDtos.WorkflowResponse response = WebDtos.from(workflow.approve(workflowId, decision));
        auditTrail.record(role == null ? "APPROVER" : DemoRole.parse(role).name(), "APPROVE_WORKFLOW", workflowId, "SUCCESS");
        return response;
    }
}
