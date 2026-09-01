package com.example.smartpark.governance;

import com.example.smartpark.operations.OperationsCapabilitiesSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Safe, aggregated governance data for the showcase workbench.
 *
 * <p>This DTO intentionally contains no raw prompts, documents, credentials,
 * connection details, or model responses.</p>
 */
public record GovernanceOverview(
        Instant capturedAt,
        ScenarioCounts scenarios,
        OperationsCapabilitiesSnapshot capabilities,
        BusinessCounts business,
        GovernanceCounts governance,
        List<String> boundaries) {

    public GovernanceOverview {
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        scenarios = Objects.requireNonNull(scenarios, "scenarios");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        business = Objects.requireNonNull(business, "business");
        governance = Objects.requireNonNull(governance, "governance");
        boundaries = List.copyOf(Objects.requireNonNull(boundaries, "boundaries"));
    }

    public record ScenarioCounts(long total, long ready, long notReady, long disabled) { }

    public record BusinessCounts(
            long workflowCount,
            long completedWorkflowCount,
            long customerSessionCount,
            long humanTicketCount) { }

    public record GovernanceCounts(
            long auditEntryCount,
            long feedbackCount,
            long positiveFeedbackCount,
            long knowledgeDocumentCount,
            long activeKnowledgeDocumentCount,
            Double completionRate,
            Double positiveFeedbackRate) { }
}
