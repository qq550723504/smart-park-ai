package com.example.smartpark.analytics.report;

import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** Durable report reads remain available even when new analytics generation is disabled. */
@Configuration(proxyBeanMethods = false)
public class OperationsReportConfiguration {

    @Bean
    OperationsDailyReportStore operationsDailyReportStore(
            @Value("${smartpark.reporting.state-file:./data/reports/reports.json}") String stateFile,
            @Value("${smartpark.reporting.max-retained-reports:200}") int maxRetainedReports,
            @Value("${smartpark.reporting.max-active-reports:1}") int maxActiveReports,
            @Value("${smartpark.reporting.max-report-bytes:524288}") int maxReportBytes,
            @Value("${smartpark.reporting.max-artifact-bytes:262144}") int maxArtifactBytes,
            ExecutionEventPublisher publisher) {
        return new OperationsDailyReportStore(Path.of(stateFile), new ObjectMapper().findAndRegisterModules(),
                maxRetainedReports, maxActiveReports, maxReportBytes, maxArtifactBytes,
                report -> {
                    var durableHistory = report.traceEvents().stream()
                            .map(trace -> trace.toExecutionEvent(report.traceId())).toList();
                    if (!durableHistory.isEmpty()) {
                        publisher.reconcileTerminalHistory(report.traceId(), durableHistory);
                    }
                    publisher.remove(report.traceId());
                });
    }

    @Bean
    OperationsReportRenderer operationsReportRenderer() {
        return new OperationsReportRenderer();
    }

    @Bean
    ExecutionEventArchive operationsReportTraceArchive(OperationsDailyReportStore reportStore,
                                                        ExecutionEventPublisher publisher) {
        return new OperationsReportTraceArchive(reportStore, publisher);
    }

    @Bean
    OperationsDailyReportService operationsDailyReportService(
            ObjectProvider<OperationsReportSectionRunner> sectionRunner,
            OperationsDailyReportStore reportStore,
            ExecutionEventPublisher publisher,
            OperationsReportRenderer renderer) {
        OperationsReportSectionRunner availableRunner = sectionRunner.getIfAvailable();
        boolean generationAvailable = availableRunner != null;
        OperationsReportSectionRunner safeRunner = generationAvailable ? availableRunner
                : (section, request) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new OperationsReportUnavailableException("operations analytics is disabled"));
        return new OperationsDailyReportService(safeRunner, reportStore, publisher, renderer,
                Clock.systemUTC(), generationAvailable);
    }
}
