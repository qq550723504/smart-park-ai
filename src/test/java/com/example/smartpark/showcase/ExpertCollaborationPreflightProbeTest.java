package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;

class ExpertCollaborationPreflightProbeTest {

    @Test
    void collaborationPassesOnlyWithEvidenceBackedThreeDomainResult() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        List<ExpertFinding> findings = List.of(
                supported(ExpertDomain.ENERGY),
                supported(ExpertDomain.DEVICE),
                supported(ExpertDomain.SECURITY));
        when(service.get(runId)).thenReturn(completed(runId, findings,
                new Synthesis(FindingStatus.SUPPORTED,
                        "ENERGY evidence；DEVICE evidence；SECURITY evidence",
                        findings.stream().flatMap(finding -> finding.evidenceRefs().stream()).toList(),
                        .8, List.of())));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.PASSED);
        verify(service).start("电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联");
    }

    @Test
    void collaborationRejectsCompletedRunWithFindingsButNoEvidence() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        List<ExpertFinding> findings = java.util.Arrays.stream(ExpertDomain.values())
                .map(domain -> new ExpertFinding(domain, FindingStatus.INSUFFICIENT_EVIDENCE,
                        "no evidence", List.of(), 0, List.of("retry")))
                .toList();
        when(service.get(runId)).thenReturn(completed(runId, findings,
                new Synthesis(FindingStatus.INSUFFICIENT_EVIDENCE, "no verified conclusion",
                        List.of(), 0, List.of("missing evidence"))));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void collaborationRetriesOneTerminalProviderFailure() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID firstFailedId = UUID.randomUUID();
        UUID passedId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(
                run(firstFailedId, CollaborationRun.RunStatus.RUNNING, List.of()),
                run(passedId, CollaborationRun.RunStatus.RUNNING, List.of()));
        when(service.get(firstFailedId)).thenReturn(run(
                firstFailedId, CollaborationRun.RunStatus.FAILED, List.of()));
        List<ExpertFinding> findings = List.of(
                supported(ExpertDomain.ENERGY),
                supported(ExpertDomain.DEVICE),
                supported(ExpertDomain.SECURITY));
        when(service.get(passedId)).thenReturn(completed(passedId, findings,
                new Synthesis(FindingStatus.SUPPORTED, "verified evidence",
                        findings.stream().flatMap(finding -> finding.evidenceRefs().stream()).toList(),
                        .8, List.of())));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.PASSED);
        verify(service, times(2)).start(anyString());
    }

    @Test
    void collaborationRejectsMissingDomainFinding() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        List<ExpertFinding> findings = List.of(
                supported(ExpertDomain.ENERGY),
                supported(ExpertDomain.DEVICE));
        when(service.get(runId)).thenReturn(completed(runId, findings,
                new Synthesis(FindingStatus.SUPPORTED, "ENERGY evidence；DEVICE evidence",
                        findings.stream().flatMap(finding -> finding.evidenceRefs().stream()).toList(),
                        .8, List.of())));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void collaborationRejectsNonSupportedDomainFinding() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        List<ExpertFinding> findings = List.of(
                supported(ExpertDomain.ENERGY),
                new ExpertFinding(ExpertDomain.DEVICE, FindingStatus.INSUFFICIENT_EVIDENCE,
                        "no device evidence", List.of(), 0, List.of("retry")),
                supported(ExpertDomain.SECURITY));
        List<String> supportedEvidence = List.of("tool:energy#fixture", "tool:security#fixture");
        when(service.get(runId)).thenReturn(completed(runId, findings,
                new Synthesis(FindingStatus.SUPPORTED, "ENERGY evidence；SECURITY evidence",
                        supportedEvidence, .8, List.of("device evidence missing"))));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void collaborationRejectsSynthesisEvidenceMismatch() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        List<ExpertFinding> findings = List.of(
                supported(ExpertDomain.ENERGY),
                supported(ExpertDomain.DEVICE),
                supported(ExpertDomain.SECURITY));
        when(service.get(runId)).thenReturn(completed(runId, findings,
                new Synthesis(FindingStatus.SUPPORTED,
                        "ENERGY evidence；DEVICE evidence；SECURITY evidence",
                        List.of("tool:energy#fixture", "tool:device#fixture"), .8, List.of())));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = CollaborationRun.RunStatus.class,
            names = {"FAILED", "NEEDS_CLARIFICATION"})
    void collaborationRejectsNonCompletedTerminalStates(CollaborationRun.RunStatus status) {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        when(service.get(runId)).thenReturn(run(runId, status, List.of(mock(ExpertFinding.class))));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void collaborationRejectsCompletedRunWithNoFindings() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        when(service.get(runId)).thenReturn(run(runId, CollaborationRun.RunStatus.COMPLETED, List.of()));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void awaiterRestoresInterruptAndFails() {
        Thread.currentThread().interrupt();
        try {
            assertThat(new ShowcaseProbeAwaiter().await(() -> "RUNNING", ignored -> null))
                    .isEqualTo(ShowcaseProbeResult.FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void abortsOwnedCollaborationWhenPreflightAwaitIsInterrupted() throws Exception {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        CountDownLatch polled = new CountDownLatch(1);
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        when(service.get(runId)).thenAnswer(invocation -> {
            polled.countDown();
            return run(runId, CollaborationRun.RunStatus.RUNNING, List.of());
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> new ExpertCollaborationPreflightProbe(service).probe());
            assertThat(polled.await(1, TimeUnit.SECONDS)).isTrue();
            future.cancel(true);
            verify(service, timeout(1_000)).abort(runId);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CollaborationRun run(UUID runId, CollaborationRun.RunStatus status,
                                        List<ExpertFinding> findings) {
        return new CollaborationRun(runId, "question", status, null, findings, null, null, Instant.EPOCH);
    }

    private static CollaborationRun completed(UUID runId, List<ExpertFinding> findings,
                                              Synthesis synthesis) {
        String question = "question";
        EnumMap<ExpertDomain, String> assignments = new EnumMap<>(ExpertDomain.class);
        for (ExpertDomain domain : ExpertDomain.values()) {
            assignments.put(domain, question);
        }
        SupervisorPlan plan = new SupervisorPlan(question, EnumSet.allOf(ExpertDomain.class),
                assignments, "all fixture domains are required");
        return new CollaborationRun(runId, question, CollaborationRun.RunStatus.COMPLETED,
                plan, findings, synthesis, null, Instant.EPOCH);
    }

    private static ExpertFinding supported(ExpertDomain domain) {
        return new ExpertFinding(domain, FindingStatus.SUPPORTED, domain + " evidence",
                List.of("tool:" + domain.name().toLowerCase() + "#fixture"), .8, List.of());
    }
}
