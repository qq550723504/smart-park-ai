package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.customer.CustomerServiceExecutionService;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/customer-service")
public class CustomerServiceController {

    private final CustomerServiceWorkflow workflow;
    private final AuditTrail auditTrail;
    private final CustomerServiceExecutionService executionService;

    public CustomerServiceController(CustomerServiceWorkflow workflow) {
        this(workflow, new AuditTrail(), null);
    }

    public CustomerServiceController(CustomerServiceWorkflow workflow, AuditTrail auditTrail) {
        this(workflow, auditTrail, null);
    }

    @Autowired
    public CustomerServiceController(CustomerServiceWorkflow workflow, AuditTrail auditTrail,
                                     ExecutionEventPublisher publisher) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.executionService = new CustomerServiceExecutionService(workflow, publisher);
    }

    @PostMapping("/sessions")
    public ResponseEntity<WebDtos.CustomerServiceResponse> ask(
            @Valid @RequestBody WebDtos.CustomerServiceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var execution = executionService.handle(request.question(), idempotencyKey);
        WebDtos.CustomerServiceResponse response = WebDtos.from(execution.result());
        auditTrail.record("ANONYMOUS", "CREATE_CUSTOMER_SESSION", response.sessionId(), "SUCCESS");
        return ResponseEntity.ok().header("X-Execution-Run-Id", execution.runId().toString()).body(response);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<WebDtos.CustomerServiceResponse> reply(
            @PathVariable String sessionId,
            @Valid @RequestBody WebDtos.CustomerServiceRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var execution = executionService.reply(sessionId, request.question(), idempotencyKey);
        WebDtos.CustomerServiceResponse response = WebDtos.from(execution.result());
        auditTrail.record("ANONYMOUS", "ADD_CUSTOMER_MESSAGE", sessionId, "SUCCESS");
        return ResponseEntity.ok().header("X-Execution-Run-Id", execution.runId().toString()).body(response);
    }

    @GetMapping("/sessions/{sessionId}/conversation")
    public WebDtos.CustomerConversationResponse conversation(@PathVariable String sessionId) {
        return WebDtos.from(workflow.conversation(sessionId));
    }

    @GetMapping("/tickets")
    public List<WebDtos.CustomerServiceResponse> tickets(
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.CUSTOMER_AGENT, DemoRole.ADMIN);
        return workflow.tickets().stream().map(WebDtos::from).toList();
    }

    @PatchMapping("/tickets/{ticketId}")
    public WebDtos.CustomerServiceResponse updateTicket(
            @PathVariable String ticketId,
            @Valid @RequestBody WebDtos.CustomerTicketUpdateRequest request,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.CUSTOMER_AGENT, DemoRole.ADMIN);
        WebDtos.CustomerServiceResponse response = WebDtos.from(workflow.updateTicket(ticketId, request.status()));
        auditTrail.record(DemoRole.parse(role).name(), "UPDATE_CUSTOMER_TICKET", ticketId, "SUCCESS");
        return response;

    }

    @GetMapping("/sessions/{sessionId}")
    public WebDtos.CustomerServiceResponse get(@PathVariable String sessionId) {
        return WebDtos.from(workflow.get(sessionId));
    }
}
