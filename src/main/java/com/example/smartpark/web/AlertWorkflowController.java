package com.example.smartpark.web;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.park.AlertPort;
import com.example.smartpark.park.mock.MockParkSystem;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.Objects;

@RestController
@RequestMapping("/api")
@Validated
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertWorkflowController {

    private final AlertWorkflow workflow;
    private final AlertPort alertPort;

    public AlertWorkflowController(AlertWorkflow workflow, AlertPort alertPort) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.alertPort = Objects.requireNonNull(alertPort, "alertPort");
    }

    @PostMapping("/alerts/{alertId}/workflows")
    public WebDtos.WorkflowResponse start(@PathVariable String alertId) {
        requireKnownAlert(alertId);
        return WebDtos.from(workflow.start(alertId));
    }

    @GetMapping("/workflows/{workflowId}")
    public WebDtos.WorkflowResponse status(@PathVariable String workflowId) {
        return WebDtos.from(workflow.status(workflowId));
    }

    private void requireKnownAlert(String alertId) {
        try {
            alertPort.getAlert(alertId);
        }
        catch (IllegalArgumentException exception) {
            throw new NoSuchElementException("Unknown alert: " + alertId, exception);
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
class AlertWorkflowRuntimeConfiguration {

    @Bean
    MockParkSystem mockParkSystem() {
        return new MockParkSystem();
    }

    @Bean
    WorkflowExecutionStore workflowExecutionStore() {
        return WorkflowExecutionStore.inMemory();
    }

    @Bean
    WorkflowEventPublisher workflowEventPublisher() {
        return WorkflowEventPublisher.inMemory();
    }

    @Bean
    AlertWorkflow alertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            MockParkSystem parkSystem,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher) {
        return new AlertWorkflow(
                triageAgent,
                diagnosisAgent,
                parkSystem,
                parkSystem,
                parkSystem,
                parkSystem,
                executionStore,
                eventPublisher);
    }
}
