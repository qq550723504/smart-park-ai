package com.example.smartpark.execution.model;

/** What happened; COMPLETED, FAILED and INTERRUPTED close the run stream. */
public enum ExecutionEventType {
    RUN_STARTED,
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
        return this == COMPLETED || this == FAILED || this == INTERRUPTED;
    }
}
