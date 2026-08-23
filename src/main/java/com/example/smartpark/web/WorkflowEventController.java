package com.example.smartpark.web;

import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Objects;

@RestController
@RequestMapping("/api/workflows")
@Validated
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowEventController {

    private final AlertWorkflow workflow;
    private final WorkflowEventPublisher eventPublisher;

    public WorkflowEventController(AlertWorkflow workflow, WorkflowEventPublisher eventPublisher) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    @GetMapping("/{workflowId}/observability")
    public WebDtos.WorkflowObservabilityResponse observability(@PathVariable String workflowId) {
        workflow.status(workflowId);
        var history = eventPublisher.history(workflowId);
        var tools = history.stream()
                .filter(event -> event.eventType() == WorkflowEvent.EventType.TOOL_CALLED)
                .map(WorkflowEvent::redactedSummary)
                .distinct()
                .toList();
        var failedNodes = history.stream()
                .filter(event -> event.eventType() == WorkflowEvent.EventType.FAILED)
                .map(WorkflowEvent::node)
                .distinct()
                .toList();
        long toolCalls = history.stream()
                .filter(event -> event.eventType() == WorkflowEvent.EventType.TOOL_CALLED)
                .count();
        return new WebDtos.WorkflowObservabilityResponse(
                workflowId, history.size(), toolCalls, tools, failedNodes);
    }

    @GetMapping(path = "/{workflowId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<WebDtos.WorkflowEventDto>> events(@PathVariable String workflowId) {
        workflow.status(workflowId);
        return eventPublisher.events(workflowId)
                .takeUntil(WorkflowEventController::isTerminal)
                .map(WebDtos::from)
                .map(event -> ServerSentEvent.<WebDtos.WorkflowEventDto>builder()
                        .id(event.eventId())
                        .event(event.type())
                        .data(event)
                        .build());
    }

    private static boolean isTerminal(WorkflowEvent event) {
        return event.eventType() == WorkflowEvent.EventType.COMPLETED
                || event.eventType() == WorkflowEvent.EventType.FAILED;
    }
}
