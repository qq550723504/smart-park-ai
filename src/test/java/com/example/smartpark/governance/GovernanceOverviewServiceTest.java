package com.example.smartpark.governance;

import com.example.smartpark.operations.OperationsCapabilitiesService;
import com.example.smartpark.operations.OperationsCapabilitiesSnapshot;
import com.example.smartpark.operations.OperationsMetrics;
import com.example.smartpark.showcase.ShowcaseScenario;
import com.example.smartpark.showcase.ShowcaseScenarioCatalog;
import com.example.smartpark.showcase.ShowcaseScenarioId;
import com.example.smartpark.showcase.ShowcaseScenarioStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void aggregatesSafeCountsAndComputesDefinedRates() {
        OperationsCapabilitiesService capabilities = mock(OperationsCapabilitiesService.class);
        when(capabilities.snapshot()).thenReturn(new OperationsCapabilitiesSnapshot(
                "rag", "dashscope", "simple-vector-store", true, true, false, false));
        OperationsMetrics metrics = mock(OperationsMetrics.class);
        when(metrics.snapshot()).thenReturn(new OperationsMetrics.Snapshot(
                4, 3, 5, 2, 7, 4, 3, 6, 5));
        ShowcaseScenarioCatalog catalog = mock(ShowcaseScenarioCatalog.class);
        when(catalog.scenarios(NOW)).thenReturn(List.of(readyScenario(), notReadyScenario()));

        GovernanceOverview overview = new GovernanceOverviewService(
                Clock.fixed(NOW, ZoneOffset.UTC), capabilities, metrics, catalog).snapshot();

        assertThat(overview.capturedAt()).isEqualTo(NOW);
        assertThat(overview.scenarios().total()).isEqualTo(2);
        assertThat(overview.scenarios().ready()).isEqualTo(1);
        assertThat(overview.scenarios().notReady()).isEqualTo(1);
        assertThat(overview.business().workflowCount()).isEqualTo(4);
        assertThat(overview.business().completedWorkflowCount()).isEqualTo(3);
        assertThat(overview.governance().completionRate()).isEqualTo(0.75);
        assertThat(overview.governance().positiveFeedbackRate()).isEqualTo(0.75);
        assertThat(overview.boundaries()).contains("演示角色，不是生产认证");
        assertThat(overview.boundaries()).anyMatch(boundary ->
                boundary.contains("客服知识问答") && boundary.contains("会话消息"));
        assertThat(overview.toString()).doesNotContain("resource", "secret", "raw");
    }

    @Test
    void usesNullRatesWhenThereAreNoSamples() {
        OperationsCapabilitiesService capabilities = mock(OperationsCapabilitiesService.class);
        when(capabilities.snapshot()).thenReturn(new OperationsCapabilitiesSnapshot(
                "mock", "mock", "none", false, false, false, false));
        OperationsMetrics metrics = mock(OperationsMetrics.class);
        when(metrics.snapshot()).thenReturn(new OperationsMetrics.Snapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0));
        ShowcaseScenarioCatalog catalog = mock(ShowcaseScenarioCatalog.class);
        when(catalog.scenarios(NOW)).thenReturn(List.of());

        GovernanceOverview overview = new GovernanceOverviewService(
                Clock.fixed(NOW, ZoneOffset.UTC), capabilities, metrics, catalog).snapshot();

        assertThat(overview.governance().completionRate()).isNull();
        assertThat(overview.governance().positiveFeedbackRate()).isNull();
    }

    private static ShowcaseScenario readyScenario() {
        return new ShowcaseScenario(ShowcaseScenarioId.CUSTOMER_SERVICE,
                ShowcaseScenarioStatus.READY, true, "客服", "停车问题", 30,
                List.of("知识"), List.of("引用"), "报修转人工", null, NOW);
    }

    private static ShowcaseScenario notReadyScenario() {
        return new ShowcaseScenario(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                ShowcaseScenarioStatus.NOT_READY, false, "分析", "能耗问题", 30,
                List.of("只读"), List.of("查询"), "只读", "本次部署尚未完成在线验证", null);
    }
}
