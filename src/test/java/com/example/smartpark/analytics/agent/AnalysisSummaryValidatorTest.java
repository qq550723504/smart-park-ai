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
    void rejectsUnsupportedFiguresAdjacentToChineseText() {
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "B1能耗为9999kWh。", plan, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数字");
    }

    @Test
    void rejectsEmptyConclusion() {
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate("  ", plan, result))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsConclusionThatBindsEachEntityToTheWrongResultValue() {
        String reversed = "B1 总计 1444.25 kWh，B2 总计 1820.5 kWh。";

        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(reversed, plan, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
    }

    @Test
    void bindsFiguresToNonnumericDimensionValuesFromActualRows() {
        QueryPlan riskPlan = new QueryPlan("按风险等级统计告警",
                List.of(new com.example.smartpark.analytics.catalog.MetricDefinition(
                        "alert_count", "告警数量", java.util.Set.of("告警"), "条",
                        "analytics.v_alert_fact", java.util.Set.of("risk_level", "occurred_at"),
                        "COUNT(*)", "occurred_at", 7, null)),
                List.of("risk_level"), Map.of(), plan.timeRange(), 100);
        TabularResult riskResult = new TabularResult(
                List.of("risk_level", "alert_count"),
                List.of(List.of("HIGH", 10), List.of("LOW", 20)), false, 12);

        AnalysisSummaryValidator validator = new AnalysisSummaryValidator();
        assertThat(validator.validate("HIGH 有 10 条，LOW 有 20 条。", riskPlan, riskResult))
                .isNotBlank();
        assertThatThrownBy(() -> validator.validate(
                "HIGH 有 20 条，LOW 有 10 条。", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
        assertThatThrownBy(() -> validator.validate(
                "HIGH, has 20. LOW, has 10.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
    }

    @Test
    void bindsEachMetricClaimToItsOwnResultColumn() {
        var energy = new com.example.smartpark.analytics.catalog.MetricDefinition(
                "energy_kwh", "能耗", java.util.Set.of("能耗"), "kWh",
                "analytics.v_energy_hourly", java.util.Set.of("building_id", "hour_ts"),
                "SUM(kwh)", "hour_ts", 7, null);
        var deviation = new com.example.smartpark.analytics.catalog.MetricDefinition(
                "energy_deviation_pct", "能耗偏差", java.util.Set.of("偏差"), "%",
                "analytics.v_energy_hourly", java.util.Set.of("building_id", "hour_ts"),
                "AVG(deviation_pct)", "hour_ts", 7, null);
        QueryPlan multiMetricPlan = new QueryPlan("楼栋能耗和偏差", List.of(energy, deviation),
                List.of("building_id"), Map.of(), plan.timeRange(), 100);
        TabularResult multiMetricResult = new TabularResult(
                List.of("building_id", "energy_kwh", "energy_deviation_pct"),
                List.of(List.of("B1", 10, 5)), false, 12);

        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "B1 使用 5 kWh，能耗偏差为 10%。", multiMetricPlan, multiMetricResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
        assertThat(new AnalysisSummaryValidator().validate(
                "B1 使用 10 kWh，能耗偏差为 5%。", multiMetricPlan, multiMetricResult)).isNotBlank();
    }

    @Test
    void doesNotMistakeADataFigureForMetadataWhenItEqualsTheRowCount() {
        QueryPlan riskPlan = new QueryPlan("按风险等级统计告警",
                List.of(new com.example.smartpark.analytics.catalog.MetricDefinition(
                        "alert_count", "告警数量", java.util.Set.of("告警"), "条",
                        "analytics.v_alert_fact", java.util.Set.of("risk_level", "occurred_at"),
                        "COUNT(*)", "occurred_at", 7, null)),
                List.of("risk_level"), Map.of(), plan.timeRange(), 100);
        TabularResult riskResult = new TabularResult(
                List.of("risk_level", "alert_count"),
                List.of(List.of("HIGH", 2), List.of("LOW", 20)), false, 12);

        assertThat(new AnalysisSummaryValidator().validate(
                "HIGH 有 2 条，共返回 2 行数据。", riskPlan, riskResult)).isNotBlank();
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "LOW 有 2 条，共返回 2 行数据。", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
    }

    @Test
    void doesNotTreatRowCountWordsAsMetadataInsideAnEntityFact() {
        QueryPlan riskPlan = riskPlan();
        TabularResult riskResult = new TabularResult(
                List.of("risk_level", "alert_count"),
                List.of(List.of("HIGH", 10), List.of("LOW", 20)), false, 12);

        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "LOW has 2 rows.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "MEDIUM has 2 rows.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisSummaryValidator().validate(
                "LOW. It has 2 rows.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsNumericDimensionTokensBeforeTreatingLaterNumbersAsFigures() {
        QueryPlan numericPlan = new QueryPlan("按楼栋编号统计能耗", plan.metrics(),
                List.of("building_id"), Map.of(), plan.timeRange(), 100);
        TabularResult numericResult = new TabularResult(
                List.of("building_id", "total_kwh"),
                List.of(List.of(10, 100), List.of(20, 200)), false, 12);
        AnalysisSummaryValidator validator = new AnalysisSummaryValidator();

        assertThat(validator.validate("10 has 100. 20 has 200.", numericPlan, numericResult))
                .isNotBlank();
        assertThatThrownBy(() -> validator.validate(
                "10 has 20. 20 has 10.", numericPlan, numericResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("对应");
    }

    @Test
    void recognizesUnitScientificNotationAndUnicodeMinusAsFigures() {
        QueryPlan riskPlan = riskPlan();
        TabularResult riskResult = new TabularResult(
                List.of("risk_level", "alert_count"),
                List.of(List.of("HIGH", 10), List.of("LOW", -20)), false, 12);
        AnalysisSummaryValidator validator = new AnalysisSummaryValidator();

        assertThatThrownBy(() -> validator.validate("HIGH has 999kWh.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数字");
        assertThatThrownBy(() -> validator.validate("HIGH has 1e3.", riskPlan, riskResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数字");
        assertThat(validator.validate("LOW has −20.", riskPlan, riskResult)).isNotBlank();
        assertThatThrownBy(() -> validator.validate("LOW has －20.", riskPlan,
                new TabularResult(List.of("risk_level", "alert_count"),
                        List.of(List.of("LOW", 20)), false, 12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数字");
        assertThatThrownBy(() -> validator.validate("LOW has –20.", riskPlan,
                new TabularResult(List.of("risk_level", "alert_count"),
                        List.of(List.of("LOW", 20)), false, 12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数字");
    }

    @Test
    void doesNotExtractDigitsFromHyphenatedDimensionIdentifiers() {
        TabularResult identifierResult = new TabularResult(
                List.of("building_id", "total_kwh"),
                List.of(List.of("MTR-2", 10)), false, 12);

        assertThat(new AnalysisSummaryValidator().validate(
                "MTR-2 has 10.", plan, identifierResult)).isNotBlank();
    }

    private QueryPlan riskPlan() {
        return new QueryPlan("按风险等级统计告警",
                List.of(new com.example.smartpark.analytics.catalog.MetricDefinition(
                        "alert_count", "告警数量", java.util.Set.of("告警"), "条",
                        "analytics.v_alert_fact", java.util.Set.of("risk_level", "occurred_at"),
                        "COUNT(*)", "occurred_at", 7, null)),
                List.of("risk_level"), Map.of(), plan.timeRange(), 100);
    }
}
