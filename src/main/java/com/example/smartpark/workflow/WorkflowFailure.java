package com.example.smartpark.workflow;

import java.util.Objects;

final class WorkflowFailure extends RuntimeException {

    private final Code code;
    private final String safeSummary;
    private final String node;

    WorkflowFailure(Code code, String safeSummary, String node, Throwable cause) {
        super(Objects.requireNonNull(safeSummary, "safeSummary"), Objects.requireNonNull(cause, "cause"));
        this.code = Objects.requireNonNull(code, "code");
        this.safeSummary = safeSummary;
        this.node = Objects.requireNonNull(node, "node");
    }

    Code code() {
        return code;
    }

    String safeSummary() {
        return safeSummary;
    }

    String node() {
        return node;
    }

    String publicError() {
        return code.name() + ": " + safeSummary;
    }

    enum Code {
        ALERT_LOOKUP_FAILED,
        CLASSIFICATION_FAILED,
        PARK_CONTEXT_FAILED,
        KNOWLEDGE_RETRIEVAL_FAILED,
        DIAGNOSIS_FAILED,
        WORK_ORDER_FAILED,
        APPROVAL_FAILED,
        WORKFLOW_FAILED
    }
}
