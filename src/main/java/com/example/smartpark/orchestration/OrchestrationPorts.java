package com.example.smartpark.orchestration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.time.Instant;

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

        default StartedChild start(String question, BooleanSupplier cancelled) {
            return start(question);
        }

        default void cancel(UUID runId) { }
    }

    @FunctionalInterface
    public interface EnergyReader {
        EvidenceOutcome query(List<String> buildingIds);
    }

    @FunctionalInterface
    public interface DeviceHealthReader {
        EvidenceOutcome query(String alertId);
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

    @FunctionalInterface
    public interface AlertScopeReader {
        String buildingId(String alertId);
    }

    public interface WorkflowRunner {
        WorkflowOutcome start(String alertId);

        default WorkflowOutcome start(String alertId, Instant approvalExpiresAt) {
            return start(alertId);
        }

        default WorkflowOutcome startOwned(String alertId, Instant approvalExpiresAt) {
            return start(alertId, approvalExpiresAt);
        }

        WorkflowOutcome get(String workflowId);

        default WorkflowOutcome expireApproval(String workflowId, Instant approvalExpiresAt) {
            return get(workflowId);
        }

        default WorkflowOutcome cancel(String workflowId) {
            throw new UnsupportedOperationException("workflow cancellation is unavailable");
        }

        /** Parent outcome is durable; the owned child may now enter bounded terminal retention. */
        default void releaseRetention(String workflowId) {
        }
    }

    public record ChildOutcome(UUID runId, String status, String summary,
                               List<String> evidenceReferences, String partialReason,
                               String failureReason) {
        public ChildOutcome {
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }

        public ChildOutcome(UUID runId, String status, String summary,
                            List<String> evidenceReferences, String failureReason) {
            this(runId, status, summary, evidenceReferences, null, failureReason);
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
                                  String failureReason, Instant approvalExpiresAt) {
        public WorkflowOutcome {
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        }

        public WorkflowOutcome(String workflowId, String status, String summary,
                               List<String> evidenceReferences, String approvalResult,
                               String failureReason) {
            this(workflowId, status, summary, evidenceReferences, approvalResult, failureReason, null);
        }
    }
}
