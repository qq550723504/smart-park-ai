package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.FindingStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled",
        havingValue = "true", matchIfMissing = true)
public final class ExpertCollaborationPreflightProbe implements ShowcasePreflightProbe {

    private static final String QUESTION =
            "请基于证据判断 DEV-ENERGY-001 夜间能耗升高、DEV-HVAC-001 冷机离线及 SEC-ACCESS-001 门禁告警是否有关联";
    private static final Set<ExpertDomain> REQUIRED_DOMAINS = Set.of(
            ExpertDomain.ENERGY, ExpertDomain.DEVICE, ExpertDomain.SECURITY);

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
                && hasCompleteEvidence(run)) {
            return ShowcaseProbeResult.PASSED;
        }
        return ShowcaseProbeResult.FAILED;
    }

    private boolean hasCompleteEvidence(CollaborationRun run) {
        if (run.plan() == null || !run.plan().selectedDomains().equals(REQUIRED_DOMAINS)
                || run.findings().size() != REQUIRED_DOMAINS.size()
                || run.synthesis() == null
                || run.synthesis().status() != FindingStatus.SUPPORTED
                || run.synthesis().evidenceRefs().isEmpty()) {
            return false;
        }
        Set<ExpertDomain> findingDomains = run.findings().stream()
                .map(finding -> finding.domain())
                .collect(Collectors.toSet());
        if (!findingDomains.equals(REQUIRED_DOMAINS)
                || run.findings().stream().anyMatch(finding ->
                finding.status() != FindingStatus.SUPPORTED || finding.evidenceRefs().isEmpty())) {
            return false;
        }
        Set<String> findingEvidence = run.findings().stream()
                .flatMap(finding -> finding.evidenceRefs().stream())
                .collect(Collectors.toSet());
        return Set.copyOf(run.synthesis().evidenceRefs()).equals(findingEvidence);
    }
}
