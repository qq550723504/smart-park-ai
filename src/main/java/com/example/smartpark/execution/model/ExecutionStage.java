package com.example.smartpark.execution.model;

/**
 * Coarse-grained pipeline phase of an event, shared across scenarios.
 * Scenario-specific detail belongs in the typed display payload, not here.
 */
public enum ExecutionStage {
    INITIALIZATION,
    INPUT_CAPTURE,
    UNDERSTANDING,
    PLANNING,
    TOOL_EXECUTION,
    ANALYSIS,
    SQL_VALIDATION,
    QUERY_EXECUTION,
    RENDERING,
    RESPONSE_DELIVERY,
    HUMAN_APPROVAL,
    COMPLETION,
    FAILURE
}
