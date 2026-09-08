package com.example.smartpark.execution;

import com.example.smartpark.execution.model.ExecutionEvent;

import java.util.List;
import java.util.UUID;

/** Optional durable source used to rehydrate an in-memory execution stream after restart. */
public interface ExecutionEventArchive {
    List<ExecutionEvent> history(UUID runId);
}
