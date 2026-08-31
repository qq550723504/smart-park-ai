package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled",
        havingValue = "true", matchIfMissing = true)
public final class ExpertCollaborationPreflightProbe implements ShowcasePreflightProbe {

    private static final String QUESTION = "A2 夜间能耗升高且门禁告警、冷机离线，是否有关联";

    private final ExpertCollaborationService service;
    private final ShowcaseProbeAwaiter awaiter = new ShowcaseProbeAwaiter();

    public ExpertCollaborationPreflightProbe(ExpertCollaborationService service) {
        this.service = service;
    }

    @Override
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.EXPERT_COLLABORATION;
    }

    @Override
    public ShowcaseProbeResult probe() {
        CollaborationRun started = service.start(QUESTION);
        return awaiter.await(() -> service.get(started.runId()), this::terminalResult);
    }

    private ShowcaseProbeResult terminalResult(CollaborationRun run) {
        if (run != null && run.status() == CollaborationRun.RunStatus.RUNNING) {
            return null;
        }
        if (run != null && run.status() == CollaborationRun.RunStatus.COMPLETED
                && !run.findings().isEmpty()) {
            return ShowcaseProbeResult.PASSED;
        }
        return ShowcaseProbeResult.FAILED;
    }
}
