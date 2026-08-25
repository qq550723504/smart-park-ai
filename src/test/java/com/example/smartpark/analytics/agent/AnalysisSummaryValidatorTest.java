package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisSummaryValidatorTest {

    private final QueryPlan plan = new QueryPlan("上周能耗",
            List.of(new com.example.smartpark.analytics.catalog.MetricDefinition(
                    "energy_kwh", "能耗", java.util.Set.of("能耗"), "kWh",
                    "analytics.v_energy_hourly", java.util.Set.of("building_id", "hour_ts"), "SUM(kwh)", 7)),
            List.of("building_id"), Map.of(),
            new QueryPlan.TimeRange(Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z")),
            100);

    private final TabularResult result = new TabularResult(
            List.of("building_id", "total_kwh"),
            List.of(List.of("B1", "1820.5"), List.of("B2", "1444.25")),
            false,
            30);

    @Test
    void acceptsConclusionsGroundedInResultValues() {
        String conclusion = "B1 总计 1820.5 kWh，高于 B2 的 1444.25 kWh；共返回 2 行数据。";
        assertThat(new AnalysisSummaryValidator().validate(conclusion, plan, result)).isNotEmpty();
    }

    @Test
    void rejectsNumbersNotSupportedByTheResult() {
        String hallucinated = "B1 总计 9999.99 kWh，比上月增长 42%。";
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(hallucinated, plan, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结论");
    }

    @Test
    void rejectsEmptyConclusion() {
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate("  ", plan, result))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
