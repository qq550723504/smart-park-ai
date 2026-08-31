package com.example.smartpark.showcase;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.OperationsAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsAnalysisPreflightProbeTest {

    @Test
    void analyticsPassesOnlyWithCompletedRunWithRows() {
        OperationsAnalysisService service = mock(OperationsAnalysisService.class);
        UUID runId = UUID.randomUUID();
        AnalysisRunStore.RunRecord started = run(runId, "RUNNING", 0);
        AnalysisRunStore.RunRecord completed = run(runId, "COMPLETED", 1);
        when(service.start("过去5天各楼宇能耗")).thenReturn(started);
        when(service.get(runId)).thenReturn(completed);

        assertThat(new OperationsAnalysisPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.PASSED);
    }

    @Test
    void analyticsRejectsACompletedRunWithNoRows() {
        OperationsAnalysisService service = mock(OperationsAnalysisService.class);
        AnalysisRunStore.RunRecord started = mock(AnalysisRunStore.RunRecord.class);
        AnalysisRunStore.RunRecord completed = mock(AnalysisRunStore.RunRecord.class);
        UUID runId = UUID.randomUUID();
        when(started.runId()).thenReturn(runId);
        when(completed.status()).thenReturn("COMPLETED");
        when(completed.rowCount()).thenReturn(0);
        when(service.start("过去5天各楼宇能耗")).thenReturn(started);
        when(service.get(runId)).thenReturn(completed);

        assertThat(new OperationsAnalysisPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "NEEDS_CLARIFICATION"})
    void analyticsRejectsNonCompletedTerminalStates(String status) {
        OperationsAnalysisService service = mock(OperationsAnalysisService.class);
        UUID runId = UUID.randomUUID();
        when(service.start("过去5天各楼宇能耗")).thenReturn(run(runId, "RUNNING", 0));
        when(service.get(runId)).thenReturn(run(runId, status, 1));

        assertThat(new OperationsAnalysisPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void analyticsAbortsAClarificationRunUsedByPreflight() {
        OperationsAnalysisService service = mock(OperationsAnalysisService.class);
        UUID runId = UUID.randomUUID();
        when(service.start("过去5天各楼宇能耗")).thenReturn(run(runId, "RUNNING", 0));
        when(service.get(runId)).thenReturn(run(runId, "NEEDS_CLARIFICATION", 0));

        assertThat(new OperationsAnalysisPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
        verify(service).abort(runId);
    }

    @Test
    void analyticsAbortsARunningRunWhenThePreflightThreadIsInterrupted() {
        OperationsAnalysisService service = mock(OperationsAnalysisService.class);
        UUID runId = UUID.randomUUID();
        when(service.start("过去5天各楼宇能耗")).thenReturn(run(runId, "RUNNING", 0));
        when(service.get(runId)).thenReturn(run(runId, "RUNNING", 0));

        Thread.currentThread().interrupt();
        assertThat(new OperationsAnalysisPreflightProbe(service).probe())
                .isEqualTo(ShowcaseProbeResult.FAILED);
        verify(service).abort(runId);
    }

    private static AnalysisRunStore.RunRecord run(UUID runId, String status, int rowCount) {
        return new AnalysisRunStore.RunRecord(runId, "question", status, null, null,
                null, rowCount, false, 0, null, null, null, null, null);
    }
}
