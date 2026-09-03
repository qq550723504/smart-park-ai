package com.example.smartpark.collaborationcenter;

import java.util.Objects;

public record WorkItemQuery(
        CollaborationWorkItem.Source source,
        CollaborationWorkItem.Status status,
        int limit,
        SortMode sortMode,
        String workItemId) {

    public WorkItemQuery(CollaborationWorkItem.Source source, CollaborationWorkItem.Status status, int limit) {
        this(source, status, limit, SortMode.UPDATED_AT, null);
    }

    public WorkItemQuery(CollaborationWorkItem.Source source, CollaborationWorkItem.Status status, int limit,
                         SortMode sortMode) {
        this(source, status, limit, sortMode, null);
    }

    public WorkItemQuery {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        if (sortMode == null) throw new IllegalArgumentException("sortMode must not be null");
        workItemId = workItemId == null || workItemId.isBlank() ? null : workItemId.trim();
    }

    public static WorkItemQuery defaults() {
        return new WorkItemQuery(null, null, 50, SortMode.SLA, null);
    }

    public boolean accepts(CollaborationWorkItem item) {
        Objects.requireNonNull(item, "item");
        return (source == null || source == item.source())
                && (status == null || status == item.status())
                && (workItemId == null || workItemId.equals(item.id()));
    }

    public enum SortMode { SLA, UPDATED_AT }
}
