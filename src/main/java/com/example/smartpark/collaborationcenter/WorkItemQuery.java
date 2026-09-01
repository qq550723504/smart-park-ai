package com.example.smartpark.collaborationcenter;

import java.util.Objects;

public record WorkItemQuery(
        CollaborationWorkItem.Source source,
        CollaborationWorkItem.Status status,
        int limit) {

    public WorkItemQuery {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
    }

    public static WorkItemQuery defaults() {
        return new WorkItemQuery(null, null, 50);
    }

    public boolean accepts(CollaborationWorkItem item) {
        Objects.requireNonNull(item, "item");
        return (source == null || source == item.source())
                && (status == null || status == item.status());
    }
}
