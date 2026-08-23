package com.example.smartpark.web;

import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.workflow.AlertWorkflow;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Autowired
    public ApprovalController(AlertWorkflow workflow) {
        this(workflow, Clock.systemUTC());
    }

    ApprovalController(AlertWorkflow workflow, Clock clock) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/{workflowId}/approval")
    public WebDtos.WorkflowResponse approve(
            @PathVariable String workflowId,
            @Valid @RequestBody WebDtos.ApprovalRequest request) {
        ApprovalDecision decision = request.toDomain(Instant.now(clock));
        return WebDtos.from(workflow.approve(workflowId, decision));
    }
}
