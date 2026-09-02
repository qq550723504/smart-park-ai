package com.example.smartpark.web;

import com.example.smartpark.collaborationcenter.CollaborationWorkItem;
import com.example.smartpark.collaborationcenter.CollaborationSlaSnapshot;

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
            Instant openedAt,
            Instant slaDueAt,
            CollaborationWorkItem.SlaState slaState,
            String detailPath) {

        static WorkItemResponse from(CollaborationWorkItem item) {
            return new WorkItemResponse(item.id(), item.source(), item.status(), item.priority(), item.title(),
                    item.safeSummary(), item.parkId(), item.buildingId(), item.deviceId(), item.updatedAt(),
                    item.openedAt(), item.slaDueAt(), item.slaState(), item.detailPath());
        }
    }

    record SlaTrendResponse(
            Instant capturedAt,
            int total,
            int overdue,
            int dueSoon,
            int onTrack,
            int completed,
            int notApplicable) {

        static SlaTrendResponse from(CollaborationSlaSnapshot snapshot) {
            return new SlaTrendResponse(snapshot.capturedAt(), snapshot.total(), snapshot.overdue(), snapshot.dueSoon(),
                    snapshot.onTrack(), snapshot.completed(), snapshot.notApplicable());
        }
    }
}
