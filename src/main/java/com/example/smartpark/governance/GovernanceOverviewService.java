package com.example.smartpark.governance;

import com.example.smartpark.operations.OperationsCapabilitiesService;
import com.example.smartpark.operations.OperationsMetrics;
import com.example.smartpark.showcase.ShowcaseScenario;
import com.example.smartpark.showcase.ShowcaseScenarioCatalog;
import com.example.smartpark.showcase.ShowcaseScenarioStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public final class GovernanceOverviewService {

    private static final List<String> BOUNDARIES = List.of(
            "演示角色，不是生产认证",
            "当前样本与状态保存在进程内，重启后需重新验证",
            "指标为聚合统计，不展示原始内容或敏感配置",
            "只读分析与知识问答不会自动执行设备控制、审批或数据写入",
            "高风险处置仍需人工审批后才可创建工单");

    private final Clock clock;
    private final OperationsCapabilitiesService capabilities;
    private final OperationsMetrics metrics;
    private final ShowcaseScenarioCatalog scenarioCatalog;

    public GovernanceOverviewService(
            @Qualifier("showcaseClock") Clock clock,
            OperationsCapabilitiesService capabilities,
            OperationsMetrics metrics,
            ShowcaseScenarioCatalog scenarioCatalog) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scenarioCatalog = Objects.requireNonNull(scenarioCatalog, "scenarioCatalog");
    }

    public GovernanceOverview snapshot() {
        Instant capturedAt = clock.instant();
        List<ShowcaseScenario> scenarios = scenarioCatalog.scenarios(capturedAt);
        long ready = scenarios.stream().filter(s -> s.status() == ShowcaseScenarioStatus.READY).count();
        long notReady = scenarios.stream().filter(s -> s.status() == ShowcaseScenarioStatus.NOT_READY).count();
        long disabled = scenarios.stream().filter(s -> s.status() == ShowcaseScenarioStatus.DISABLED).count();

        OperationsMetrics.Snapshot metricSnapshot = metrics.snapshot();
        Double completionRate = metricSnapshot.workflowCount() == 0
                ? null
                : (double) metricSnapshot.completedWorkflowCount() / metricSnapshot.workflowCount();
        Double positiveFeedbackRate = metricSnapshot.feedbackCount() == 0
                ? null
                : (double) metricSnapshot.positiveFeedbackCount() / metricSnapshot.feedbackCount();

        return new GovernanceOverview(
                capturedAt,
                new GovernanceOverview.ScenarioCounts(scenarios.size(), ready, notReady, disabled),
                capabilities.snapshot(),
                new GovernanceOverview.BusinessCounts(
                        metricSnapshot.workflowCount(), metricSnapshot.completedWorkflowCount(),
                        metricSnapshot.customerSessionCount(), metricSnapshot.humanTicketCount()),
                new GovernanceOverview.GovernanceCounts(
                        metricSnapshot.auditEntryCount(), metricSnapshot.feedbackCount(),
                        metricSnapshot.positiveFeedbackCount(), metricSnapshot.knowledgeDocumentCount(),
                        metricSnapshot.activeKnowledgeDocumentCount(), completionRate, positiveFeedbackRate),
                BOUNDARIES);
    }
}
