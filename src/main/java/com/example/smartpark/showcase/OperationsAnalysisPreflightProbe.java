package com.example.smartpark.showcase;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.OperationsAnalysisService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smartpark.analytics", name = "enabled",
        havingValue = "true")
public final class OperationsAnalysisPreflightProbe implements ShowcasePreflightProbe {

    private final OperationsAnalysisService service;
    private final ShowcaseProbeAwaiter awaiter = new ShowcaseProbeAwaiter();

    public OperationsAnalysisPreflightProbe(OperationsAnalysisService service) {
        this.service = service;
    }

    @Override
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.OPERATIONS_ANALYSIS;
    }

    @Override
    public ShowcaseProbeResult probe() {
        AnalysisRunStore.RunRecord started = service.start(
                ShowcaseLaunchInput.forScenario(scenarioId()).question());
        return awaiter.await(() -> service.get(started.runId()), run -> {
            if (run != null && "NEEDS_CLARIFICATION".equals(run.status())) {
                service.abort(run.runId());
                return ShowcaseProbeResult.FAILED;
            }
            return terminalResult(run);
        });
    }

    private ShowcaseProbeResult terminalResult(AnalysisRunStore.RunRecord run) {
        if (run != null && "RUNNING".equals(run.status())) {
            return null;
        }
        if (run != null && "COMPLETED".equals(run.status()) && run.rowCount() > 0) {
            return ShowcaseProbeResult.PASSED;
        }
        return ShowcaseProbeResult.FAILED;
    }
}
