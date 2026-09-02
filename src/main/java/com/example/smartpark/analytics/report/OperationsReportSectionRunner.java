package com.example.smartpark.analytics.report;

import com.example.smartpark.analytics.AnalysisRunStore;

import java.util.concurrent.CompletableFuture;

/** Application boundary used by report orchestration to execute one safe section. */
@FunctionalInterface
public interface OperationsReportSectionRunner {

    CompletableFuture<AnalysisRunStore.RunRecord> run(OperationsReportSection section);
}
