package com.example.smartpark.web;

import com.example.smartpark.operations.OperationsMetrics;
import com.example.smartpark.analytics.anomaly.OperationsAnomalyDtos;
import com.example.smartpark.analytics.anomaly.OperationsAnomalyQuery;
import com.example.smartpark.analytics.anomaly.OperationsAnomalyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsMetrics metrics;
    private final OperationsAnomalyService anomalyService;

    public OperationsController(OperationsMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.anomalyService = null;
    }

    OperationsController(OperationsMetrics metrics, OperationsAnomalyService anomalyService) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.anomalyService = Objects.requireNonNull(anomalyService, "anomalyService");
    }

    @Autowired
    public OperationsController(OperationsMetrics metrics, ObjectProvider<OperationsAnomalyService> anomalyService) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.anomalyService = anomalyService.getIfAvailable();
    }

    @GetMapping("/metrics")
    public OperationsMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/anomaly-overview")
    public OperationsAnomalyDtos.Overview anomalyOverview(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceType) {
        DemoRole.require(role, DemoRole.VIEWER, DemoRole.OPERATOR, DemoRole.APPROVER, DemoRole.ADMIN);
        return requireAnomalyService().overview(new OperationsAnomalyQuery(parseInstant(from), parseInstant(to),
                buildingId, riskLevel, category, status, deviceType));
    }

    @GetMapping("/anomaly-evidence/{buildingId}")
    public OperationsAnomalyDtos.Evidence anomalyEvidence(
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @PathVariable String buildingId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceType) {
        DemoRole.require(role, DemoRole.VIEWER, DemoRole.OPERATOR, DemoRole.APPROVER, DemoRole.ADMIN);
        return requireAnomalyService().evidence(buildingId, new OperationsAnomalyQuery(parseInstant(from), parseInstant(to),
                buildingId, riskLevel, category, status, deviceType));
    }

    private OperationsAnomalyService requireAnomalyService() {
        if (anomalyService == null) throw new OperationsAnomalyService.AnomalyOverviewUnavailableException("运营异常分析未启用");
        return anomalyService;
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("时间参数格式无效");
        }
    }
}
