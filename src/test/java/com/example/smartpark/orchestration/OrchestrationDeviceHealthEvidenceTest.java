package com.example.smartpark.orchestration;

import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationDeviceHealthEvidenceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void conditionallyAddsDeviceHealthEvidenceOnlyForAlertScopedRequests() {
        AtomicInteger healthCalls = new AtomicInteger();
        OrchestrationPorts.DeviceHealthReader health = alertId -> {
            healthCalls.incrementAndGet();
            return new OrchestrationPorts.EvidenceOutcome("PARTIAL", "设备健康 DEGRADED",
                    List.of("device-health:AC-B1-07:DEGRADED", "alert:" + alertId),
                    List.of("DEVICE_SNAPSHOT", "OPERATIONS_ANALYTICS_DEMO"), List.of(), null);
        };
        OrchestrationService service = service(health);

        OrchestrationRun withAlert = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                new OrchestrationInput("研判设备告警", "ALT-TEMP-001", List.of("B1"),
                        false, false, false, false), "health-1", "tester", "VIEWER").run();
        withAlert = service.get(withAlert.id());
        assertThat(withAlert.evidence()).contains("device-health:AC-B1-07:DEGRADED");
        assertThat(withAlert.steps().stream().filter(step -> step.id().equals("collect-context")).findFirst().orElseThrow()
                .sourceReferences()).contains("OPERATIONS_ANALYTICS_DEMO");

        OrchestrationRun withoutAlert = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                new OrchestrationInput("研判楼宇异常", null, List.of("B1"),
                        false, false, false, false), "health-2", "tester", "VIEWER").run();
        withoutAlert = service.get(withoutAlert.id());
        assertThat(withoutAlert.evidence()).noneMatch(item -> item.startsWith("device-health:"));
        assertThat(healthCalls).hasValue(1);
    }

    private static OrchestrationService service(OrchestrationPorts.DeviceHealthReader health) {
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID runId = UUID.randomUUID();
            return new OrchestrationPorts.StartedChild(runId, CompletableFuture.completedFuture(
                    new OrchestrationPorts.ChildOutcome(runId, "COMPLETED", "运营分析完成",
                            List.of("operations-analysis:" + runId), null)));
        };
        return new OrchestrationService(new InMemoryOrchestrationRunStore(),
                () -> new OrchestrationPorts.Capabilities(true, false, false, false, false),
                operations, null, health, null, null, null, null,
                new InMemoryExecutionEventPublisher(), Runnable::run, CLOCK, Duration.ofMinutes(15));
    }
}
