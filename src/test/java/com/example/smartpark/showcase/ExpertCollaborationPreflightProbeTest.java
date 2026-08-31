package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.ExpertFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpertCollaborationPreflightProbeTest {

    @Test
    void collaborationPassesOnlyWithCompletedNonEmptyFindings() {
        ExpertCollaborationService service = mock(ExpertCollaborationService.class);
        UUID runId = UUID.randomUUID();
        when(service.start(anyString())).thenReturn(run(runId, CollaborationRun.RunStatus.RUNNING, List.of()));
        when(service.get(runId)).thenReturn(run(runId, CollaborationRun.RunStatus.COMPLETED,
                List.of(mock(ExpertFinding.class))));

        assertThat(new ExpertCollaborationPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.PASSED);
        verify(service).start("A2 夜间能耗升高且门禁告警、冷机离线，是否有关联");
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

    private static CollaborationRun run(UUID runId, CollaborationRun.RunStatus status,
                                        List<ExpertFinding> findings) {
        return new CollaborationRun(runId, "question", status, null, findings, null, null, Instant.EPOCH);
    }
}
