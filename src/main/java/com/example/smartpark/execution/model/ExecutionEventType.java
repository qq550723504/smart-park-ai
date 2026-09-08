package com.example.smartpark.execution.model;

/** What happened; the terminal leaf and orchestration run events close the stream. */
public enum ExecutionEventType {
    RUN_STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_SKIPPED,
    STEP_FAILED,
    WAITING_APPROVAL,
    APPROVAL_RESUMED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    TEXT_DELTA,
    TEXT_COMPLETED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    TOOL_CALL_FAILED,
    EXPERT_HANDOFF,
    NODE_STARTED,
    NODE_COMPLETED,
    PAUSED,
    RESUMED,
    SQL_GENERATED,
    SQL_VALIDATED,
    SQL_REJECTED,
    QUERY_EXECUTED,
    CHART_SPECIFIED,
    AUDIO_STARTED,
    AUDIO_CHUNK,
    AUDIO_COMPLETED,
    INTERRUPTED,
    FAILED,
    COMPLETED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == INTERRUPTED
                || this == RUN_COMPLETED || this == RUN_FAILED || this == RUN_CANCELLED;
    }
}
