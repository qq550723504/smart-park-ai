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

    private static final int MAX_ATTEMPTS = 2;

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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            CollaborationRun started = service.start(
                    ShowcaseLaunchInput.forScenario(scenarioId()).question());
            ShowcaseProbeResult result = awaiter.await(
                    () -> service.get(started.runId()), this::terminalResult);
            if (result == ShowcaseProbeResult.PASSED) {
                return result;
            }
            service.abort(started.runId());
            if (Thread.currentThread().isInterrupted()) return result;
        }
        return ShowcaseProbeResult.FAILED;
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
                || run.synthesis() == null) {
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
        if (run.synthesis().status() == FindingStatus.SUPPORTED) {
            return !run.synthesis().evidenceRefs().isEmpty()
                    && Set.copyOf(run.synthesis().evidenceRefs()).equals(findingEvidence);
        }
        // A completed supervisor may safely conclude that the available,
        // fully grounded domain observations do not establish a cross-domain
        // relationship. That is still a runnable showcase result, provided it
        // discloses the uncertainty and does not attach unsupported evidence.
        return run.synthesis().status() == FindingStatus.INSUFFICIENT_EVIDENCE
                && run.synthesis().evidenceRefs().isEmpty()
                && !run.synthesis().uncertainties().isEmpty();
    }
}
