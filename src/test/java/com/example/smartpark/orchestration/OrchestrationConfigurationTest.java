package com.example.smartpark.orchestration;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.Synthesis;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Test
    void mapsTruncatedOperationsResultToPartialOutcome() {
        UUID id = UUID.randomUUID();
        AnalysisRunStore.RunRecord run = new AnalysisRunStore.RunRecord(id, "question", "COMPLETED",
                List.of(), List.of(), "limited result", 100, true, 50, null,
                NOW, NOW, List.of("count"), List.of(List.of(100L)), null);

        OrchestrationPorts.ChildOutcome outcome = OrchestrationConfiguration.operationsOutcome(run);

        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.partialReason()).contains("截断结果集");
    }

    @Test
    void mapsInsufficientCollaborationSynthesisAndItsUncertaintiesToPartialOutcome() {
        UUID id = UUID.randomUUID();
        CollaborationRun run = new CollaborationRun(id, "question", CollaborationRun.RunStatus.COMPLETED,
                null, List.of(), new Synthesis(FindingStatus.INSUFFICIENT_EVIDENCE,
                "无法确认跨域关联", List.of("energy:1"), 0,
                List.of("设备时间窗缺失", "需要人工复核")), null, NOW);

        OrchestrationPorts.ChildOutcome outcome = OrchestrationConfiguration.collaborationOutcome(run);

        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.partialReason()).contains("设备时间窗缺失", "需要人工复核");
        assertThat(outcome.evidenceReferences()).containsExactly("energy:1");
    }
}
