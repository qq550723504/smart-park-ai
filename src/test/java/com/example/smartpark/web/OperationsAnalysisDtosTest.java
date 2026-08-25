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
                List.of(), "", 0, false, 12, null, Instant.parse("2026-08-25T00:00:00Z"),
                List.of("building_id"), List.of());

        var dto = OperationsAnalysisDtos.from(record);

        assertThat(dto).containsEntry("rowCount", 0).containsEntry("truncated", false);
    }
}
