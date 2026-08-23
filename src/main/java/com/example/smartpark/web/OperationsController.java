package com.example.smartpark.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsMetrics metrics;

    public OperationsController(OperationsMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @GetMapping("/metrics")
    public OperationsMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
