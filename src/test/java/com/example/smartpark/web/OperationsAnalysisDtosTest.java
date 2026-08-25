package com.example.smartpark.web;

import com.example.smartpark.analytics.AnalysisRunStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsAnalysisDtosTest {

    @Test
    void completedEmptyResultsExposeZeroRowMetadata() {
        var record = new AnalysisRunStore.RunRecord(UUID.randomUUID(), "无结果", "COMPLETED",
                List.of(), List.of(), "", 0, false, 12, null, Instant.parse("2026-08-25T00:00:00Z"),
                List.of("building_id"), List.of());

        var dto = OperationsAnalysisDtos.from(record);

        assertThat(dto).containsEntry("rowCount", 0).containsEntry("truncated", false);
    }

    @Test
    void clarificationStatusExposesThePendingCandidateOptions() {
        var record = new AnalysisRunStore.RunRecord(UUID.randomUUID(), "告警情况", "NEEDS_CLARIFICATION",
                List.of("“告警”可以指: 告警数量 / 高风险告警数量"),
                List.of(List.of("alert_count", "high_risk_alert_count")),
                "", 0, false, 12, null, Instant.parse("2026-08-25T00:00:00Z"),
                List.of(), List.of());

        var dto = OperationsAnalysisDtos.from(record);

        assertThat(dto).containsEntry("clarificationQuestions",
                List.of("“告警”可以指: 告警数量 / 高风险告警数量"));
        assertThat(dto).containsEntry("clarificationOptions",
                List.of(List.of("alert_count", "high_risk_alert_count")));
    }
}
