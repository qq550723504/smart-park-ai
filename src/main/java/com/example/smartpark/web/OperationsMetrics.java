package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.feedback.FeedbackService;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.port.knowledge.KnowledgeAdminPort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;

import java.util.List;
import java.util.Objects;

public final class OperationsMetrics {
    private final WorkflowExecutionStore workflowStore;
    private final CustomerServiceWorkflow customerService;
    private final AuditTrail auditTrail;
    private final FeedbackService feedbackService;
    private final KnowledgeAdminPort knowledgeAdminPort;

    public OperationsMetrics(
            WorkflowExecutionStore workflowStore,
            CustomerServiceWorkflow customerService,
            AuditTrail auditTrail) {
        this(workflowStore, customerService, auditTrail, new FeedbackService(), null);
    }

    public OperationsMetrics(
            WorkflowExecutionStore workflowStore,
            CustomerServiceWorkflow customerService,
            AuditTrail auditTrail,
            FeedbackService feedbackService,
            KnowledgeAdminPort knowledgeAdminPort) {
        this.workflowStore = workflowStore;
        this.customerService = Objects.requireNonNull(customerService, "customerService");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.feedbackService = Objects.requireNonNull(feedbackService, "feedbackService");
        this.knowledgeAdminPort = knowledgeAdminPort;
    }

    public Snapshot snapshot() {
        List<WorkflowSnapshot> workflows = workflowStore == null ? List.of() : workflowStore.snapshots();


        long completed = workflows.stream().filter(snapshot -> snapshot.status() == WorkflowStatus.COMPLETED).count();
        long humanTickets = customerService.tickets().size();
        var knowledge = knowledgeAdminPort == null ? List.<KnowledgeAdminPort.ManagedDocument>of() : knowledgeAdminPort.list();
        long activeKnowledge = knowledge.stream().filter(KnowledgeAdminPort.ManagedDocument::active).count();
        return new Snapshot(
                workflows.size(), completed, customerService.sessionCount(), humanTickets, auditTrail.entries().size(),
                feedbackService.entries().size(), feedbackService.positiveCount(), knowledge.size(), activeKnowledge);
    }

    public record Snapshot(
            long workflowCount,
            long completedWorkflowCount,
            long customerSessionCount,
            long humanTicketCount,
            long auditEntryCount,
            long feedbackCount,
            long positiveFeedbackCount,
            long knowledgeDocumentCount,
            long activeKnowledgeDocumentCount) { }
}
