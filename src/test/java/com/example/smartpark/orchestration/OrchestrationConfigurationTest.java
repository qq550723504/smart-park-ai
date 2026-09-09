package com.example.smartpark.orchestration;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.health.DeviceHealthDtos;
import com.example.smartpark.analytics.health.DeviceHealthService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.Synthesis;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestrationConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Test
    void mapsTruncatedOperationsResultToPartialOutcome() {
        UUID id = UUID.randomUUID();
        AnalysisRunStore.RunRecord run = new AnalysisRunStore.RunRecord(id, "question", "COMPLETED",
                List.of(), List.of(), "limited result", 100, true, 50, null,
                NOW, NOW, List.of("count"), List.of(List.of(100L)), null);

        OrchestrationPorts.ChildOutcome outcome = OrchestrationConfiguration.operationsOutcome(run);

        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.partialReason()).contains("截断结果集");
    }

    @Test
    void mapsInsufficientCollaborationSynthesisAndItsUncertaintiesToPartialOutcome() {
        UUID id = UUID.randomUUID();
        CollaborationRun run = new CollaborationRun(id, "question", CollaborationRun.RunStatus.COMPLETED,
                null, List.of(), new Synthesis(FindingStatus.INSUFFICIENT_EVIDENCE,
                "无法确认跨域关联", List.of("energy:1"), 0,
                List.of("设备时间窗缺失", "需要人工复核")), null, NOW);

        OrchestrationPorts.ChildOutcome outcome = OrchestrationConfiguration.collaborationOutcome(run);

        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.partialReason()).contains("设备时间窗缺失", "需要人工复核");
        assertThat(outcome.evidenceReferences()).containsExactly("energy:1");
    }

    @Test
    void onlyExposesDeviceHealthToOrchestrationWhenTelemetryIsUsable() {
        DeviceHealthService health = mock(DeviceHealthService.class);
        when(health.assessByAlertId("ALT-1")).thenReturn(Optional.of(healthResponse(
                new DeviceHealthDtos.Source("OPERATIONS_ANALYTICS_DEMO",
                        DeviceHealthDtos.Availability.UNAVAILABLE))));

        OrchestrationPorts.EvidenceOutcome unavailable =
                OrchestrationConfiguration.deviceHealthOutcome(health, "ALT-1");

        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");

        when(health.assessByAlertId("ALT-1")).thenReturn(Optional.of(healthResponse(
                new DeviceHealthDtos.Source("OPERATIONS_ANALYTICS_DEMO",
                        DeviceHealthDtos.Availability.PARTIAL))));

        OrchestrationPorts.EvidenceOutcome usable =
                OrchestrationConfiguration.deviceHealthOutcome(health, "ALT-1");

        assertThat(usable.status()).isEqualTo("PARTIAL");
        assertThat(usable.evidenceReferences()).contains("device-health:AC-B1-07:DEGRADED");
    }

    private static DeviceHealthDtos.Response healthResponse(DeviceHealthDtos.Source telemetrySource) {
        return new DeviceHealthDtos.Response("AC-B1-07", "B1", "HVAC",
                DeviceHealthDtos.HealthStatus.DEGRADED, DeviceHealthDtos.Availability.PARTIAL,
                List.of("温度持续超过阈值"),
                List.of(new DeviceHealthDtos.Evidence("TELEMETRY_THRESHOLD", "telemetry:TEMPERATURE:point",
                        NOW, "demo threshold")),
                List.of(new DeviceHealthDtos.Source("DEVICE_SNAPSHOT", DeviceHealthDtos.Availability.AVAILABLE),
                        telemetrySource), NOW);
    }

    @Test
    void backendImageOwnsTheOrchestrationVolumeMountPointBeforeDroppingPrivileges() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String compose = Files.readString(Path.of("compose.yaml"));
        String ownedMountPoint = "install -d -o app -g app /var/lib/smartpark/orchestration";

        assertThat(dockerfile).contains(ownedMountPoint, "USER app");
        assertThat(dockerfile.indexOf(ownedMountPoint)).isLessThan(dockerfile.indexOf("USER app"));
        assertThat(compose).contains("orchestration-state:/var/lib/smartpark/orchestration");
    }
}
