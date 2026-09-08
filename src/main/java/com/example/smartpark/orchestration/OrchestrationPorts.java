package com.example.smartpark.orchestration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OrchestrationPorts {
    private OrchestrationPorts() {
    }

    @FunctionalInterface
    public interface CapabilityReader {
        Capabilities current();
    }

    public record Capabilities(boolean analytics, boolean energy, boolean collaboration,
                               boolean security, boolean workflow) {
    }

    @FunctionalInterface
    public interface OperationsRunner {
        StartedChild start(String question);

        default void cancel(UUID runId) { }
    }

    @FunctionalInterface
    public interface EnergyReader {
        EvidenceOutcome query(List<String> buildingIds);
    }

    @FunctionalInterface
    public interface CollaborationRunner {
        StartedChild start(String question);

        default void cancel(UUID runId) { }
    }

    @FunctionalInterface
    public interface SecurityReader {
        EvidenceOutcome query(OrchestrationInput input);
    }

    public interface WorkflowRunner {
        WorkflowOutcome start(String alertId);

        WorkflowOutcome get(String workflowId);

        default void cancel(String workflowId) {
            // The current Alert Workflow has no safe interrupt boundary.
        }
    }

    public record ChildOutcome(UUID runId, String status, String summary,
                               List<String> evidenceReferences, String failureReason) {
        public ChildOutcome {
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }
    }

    public record StartedChild(UUID runId, CompletableFuture<ChildOutcome> completion) {
    }

    public record EvidenceOutcome(String status, String summary,
                                  List<String> evidenceReferences, List<String> sourceReferences,
                                  List<String> recommendations, String failureReason) {
        public EvidenceOutcome {
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
            sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
            recommendations = List.copyOf(recommendations == null ? List.of() : recommendations);
        }
    }

    public record WorkflowOutcome(String workflowId, String status, String summary,
                                  List<String> evidenceReferences, String approvalResult,
                                  String failureReason) {
        public WorkflowOutcome {
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }
    }
}
