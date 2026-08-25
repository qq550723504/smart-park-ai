package com.example.smartpark.execution;

import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

/**
 * Decorator around the legacy alert workflow publisher that synchronously projects
 * every published event into the unified execution trace. Projection failures fail
 * the legacy publish path explicitly — traces are never silently dropped.
 */
public final class ProjectedWorkflowEventPublisher implements WorkflowEventPublisher {

    private final WorkflowEventPublisher delegate;
    private final LegacyWorkflowEventAdapter adapter;

    public ProjectedWorkflowEventPublisher(WorkflowEventPublisher delegate, LegacyWorkflowEventAdapter adapter) {
        this.delegate = delegate;
        this.adapter = adapter;
    }

    @Override
    public WorkflowEvent publish(String workflowId, WorkflowEvent.EventType eventType, String node, Instant timestamp, String summary) {
        WorkflowEvent event = delegate.publish(workflowId, eventType, node, timestamp, summary);
        adapter.project(event);
        return event;
    }

    @Override
    public Flux<WorkflowEvent> events(String workflowId) {
        return delegate.events(workflowId);
    }

    @Override
    public List<WorkflowEvent> history(String workflowId) {
        return delegate.history(workflowId);
    }

    @Override
    public void complete(String workflowId) {
        delegate.complete(workflowId);
    }
}
