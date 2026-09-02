package com.example.smartpark.web;

import com.example.smartpark.collaborationcenter.CollaborationCenterService;
import com.example.smartpark.collaborationcenter.CollaborationSlaSnapshotStore;
import com.example.smartpark.collaborationcenter.CollaborationWorkItem;
import com.example.smartpark.collaborationcenter.WorkItemQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@ConditionalOnBean(CollaborationCenterService.class)
public class CollaborationCenterController {
    private final CollaborationCenterService service;

    public CollaborationCenterController(CollaborationCenterService service) {
        this.service = service;
    }

    @GetMapping("/api/collaboration/work-items")
    public List<CollaborationCenterDtos.WorkItemResponse> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "sla") String sort,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.CUSTOMER_AGENT, DemoRole.APPROVER, DemoRole.ADMIN);
        WorkItemQuery query = new WorkItemQuery(parse(source, CollaborationWorkItem.Source.class, "source"),
                parse(status, CollaborationWorkItem.Status.class, "status"), limit,
                parseSort(sort));
        return service.list(query).stream().map(CollaborationCenterDtos.WorkItemResponse::from).toList();
    }

    @GetMapping("/api/collaboration/sla-trend")
    public List<CollaborationCenterDtos.SlaTrendResponse> trend(
            @RequestParam(defaultValue = "60") int limit,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.CUSTOMER_AGENT, DemoRole.APPROVER, DemoRole.ADMIN);
        if (limit < 1 || limit > CollaborationSlaSnapshotStore.MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + CollaborationSlaSnapshotStore.MAX_SNAPSHOTS);
        }
        return service.listTrend(limit).stream().map(CollaborationCenterDtos.SlaTrendResponse::from).toList();
    }

    private static WorkItemQuery.SortMode parseSort(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("UPDATEDAT".equals(normalized)) normalized = "UPDATED_AT";
        try {
            return normalized.isBlank() ? WorkItemQuery.SortMode.SLA
                    : Enum.valueOf(WorkItemQuery.SortMode.class, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sort is not supported");
        }
    }

    private static <E extends Enum<E>> E parse(String raw, Class<E> type, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is not supported");
        }
    }
}
