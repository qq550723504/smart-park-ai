package com.example.smartpark.analytics;

import com.example.smartpark.analytics.report.OperationsReportRequest;
import com.example.smartpark.analytics.report.OperationsReportSection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsReportSectionRunnerTest {

    @Test
    void queuesAdmissionWithoutBlockingTheReportCaller() throws Exception {
        OperationsAnalysisService analysis = mock(OperationsAnalysisService.class);
        UUID runId = UUID.randomUUID();
        AnalysisRunStore.RunRecord completed = new AnalysisRunStore.RunRecord(runId, "question", "COMPLETED",
                List.of(), List.of(), "summary", 0, false, 1, null,
                Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-09T00:00:01Z"),
                List.of(), List.of(), null);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        when(analysis.startWhenAvailable(anyString(), any())).thenAnswer(invocation -> {
            assertThat(releaseAdmission.await(2, TimeUnit.SECONDS)).isTrue();
            return completed;
        });
        when(analysis.await(runId)).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(completed));
        AnalyticsConfiguration configuration = new AnalyticsConfiguration();
        ExecutorService executor = configuration.operationsReportAdmissionExecutor(1);
        try {
            var runner = configuration.operationsReportSectionRunner(analysis, executor);
            var result = runner.run(new OperationsReportSection("energy", "Energy", "过去5天能耗"),
                    new OperationsReportRequest(OperationsReportRequest.DAILY,
                            new OperationsReportRequest.TimeWindow(
                                    Instant.parse("2026-09-04T00:00:00Z"),
                                    Instant.parse("2026-09-09T00:00:00Z")),
                            "Asia/Shanghai"));

            assertThat(result.isDone()).isFalse();
            releaseAdmission.countDown();

            assertThat(result.get(2, TimeUnit.SECONDS)).isSameAs(completed);
            verify(analysis).startWhenAvailable(anyString(), any());
            verify(analysis).await(runId);
        } finally {
            executor.shutdownNow();
        }
    }
}
