package com.example.smartpark.web;

import com.example.smartpark.collaborationcenter.CollaborationWorkItem;

import java.time.Instant;

final class CollaborationCenterDtos {
    private CollaborationCenterDtos() { }

    record WorkItemResponse(
            String id,
            CollaborationWorkItem.Source source,
            CollaborationWorkItem.Status status,
            CollaborationWorkItem.Priority priority,
            String title,
            String safeSummary,
            String parkId,
            String buildingId,
            String deviceId,
            Instant updatedAt,
            String detailPath) {

        static WorkItemResponse from(CollaborationWorkItem item) {
            return new WorkItemResponse(item.id(), item.source(), item.status(), item.priority(), item.title(),
                    item.safeSummary(), item.parkId(), item.buildingId(), item.deviceId(), item.updatedAt(),
                    item.detailPath());
        }
    }
}
