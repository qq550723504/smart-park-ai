package com.example.smartpark.analytics.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartSpecTest {

    private final TabularResult result = new TabularResult(
            List.of("building_id", "total_kwh"),
            List.of(List.of("B1", "100")),
            false, 10);

    @Test
    void acceptsProposalReferencingOnlyRealColumns() {
        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "能耗趋势", "building_id", List.of("total_kwh"), "", "kWh"), result);
        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.LINE);
        assertThat(spec.yFields()).containsExactly("total_kwh");
    }

    @Test
    void unknownChartTypesFallBackToTableInsteadOfFabricating() {
        ChartSpec fallback = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "PIE", "饼图", "building_id", List.of("total_kwh"), "", ""), result);
        assertThat(fallback.type()).isEqualTo(ChartSpec.ChartType.TABLE);
    }

    @Test
    void unknownColumnsDegradeToPlainTable() {
        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "BAR", "趋势", "ghost_column", List.of("another_ghost"), "", "kWh"), result);
        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
        assertThat(spec.xField()).isEqualTo("building_id");
        assertThat(spec.yFields()).isEmpty();
    }

    @Test
    void rejectsCategoricalColumnsAsChartYFields() {
        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "错误图表", "building_id", List.of("building_id"), "", ""), result);

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
    }

    @Test
    void preservesNullCellsWhileMakingRowsImmutable() {
        TabularResult nullable = new TabularResult(
                List.of("building_id", "optional_value"),
                List.of(Arrays.asList("B1", null)), false, 1);

        assertThat(nullable.rows().get(0)).containsExactly("B1", null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> nullable.rows().get(0).set(0, "B2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateXCoordinatesDegradeToTableInsteadOfSilentlyOverwriting() {
        // Two rows share the same x coordinate; a LINE/BAR chart would keep
        // only the last value for it and silently drop the other.
        TabularResult duplicated = new TabularResult(
                List.of("building_id", "total_kwh"),
                List.of(List.of("B1", "100"), List.of("B1", "200")),
                false, 10);

        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "重复坐标", "building_id", List.of("total_kwh"), "", "kWh"), duplicated);

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
    }

    @Test
    void duplicateXAndSeriesCoordinatesDegradeToTable() {
        // Same (xField, seriesField) pair appearing twice must not collapse to
        // the last-written point.
        TabularResult grouped = new TabularResult(
                List.of("building_id", "meter_id", "total_kwh"),
                List.of(List.of("B1", "M1", "100"), List.of("B2", "M2", "200"),
                        List.of("B1", "M1", "300")),
                false, 10);

        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "BAR", "重复系列坐标", "building_id", List.of("total_kwh"), "meter_id", "kWh"), grouped);

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
    }

    @Test
    void uniqueCoordinatesStillChart() {
        TabularResult grouped = new TabularResult(
                List.of("building_id", "meter_id", "total_kwh"),
                List.of(List.of("B1", "M1", "100"), List.of("B2", "M2", "200")),
                false, 10);

        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "BAR", "正常图表", "building_id", List.of("total_kwh"), "meter_id", "kWh"), grouped);

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.BAR);
        assertThat(spec.seriesField()).isEqualTo("meter_id");
    }

    @Test
    void nonLineBarChartsRequireAtLeastOneYField() {
        assertThatThrownBy(() -> new ChartSpec(ChartSpec.ChartType.BAR, "t", "x",
                List.of(), "-", ""))
                .isInstanceOf(IllegalArgumentException.class);
        // TABLE may legitimately have no yFields.
        assertThat(new ChartSpec(ChartSpec.ChartType.TABLE, "t", "building_id",
                List.of(), "-", "").yFields()).isEmpty();
    }

    @Test
    void derivesUnitFromPlannedMetricsInsteadOfTrustingTheProposal() {
        // The model proposed "%" but the planned metric is kWh: the catalog
        // definition wins, so the trace cannot misread energy as a percentage.
        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "能耗", "building_id", List.of("total_kwh"), "", "%"), result,
                java.util.Map.of("total_kwh", "kWh"));

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.LINE);
        assertThat(spec.unit()).isEqualTo("kWh");
    }

    @Test
    void mixedMetricUnitsDegradeToTable() {
        TabularResult mixed = new TabularResult(
                List.of("building_id", "total_kwh", "deviation_pct"),
                List.of(List.of("B1", "100", "5.2")), false, 1);

        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "混合单位", "building_id", List.of("total_kwh", "deviation_pct"), "", ""),
                mixed, java.util.Map.of("total_kwh", "kWh", "deviation_pct", "%"));

        // One axis label cannot represent both kWh and % values.
        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
        assertThat(spec.unit()).isEmpty();
    }

    @Test
    void chartYFieldWithoutAPlannedMetricUnitDegradesToTable() {
        ChartSpec spec = ChartSpec.fromProposal(new ChartSpec.Proposal(
                "LINE", "无单位", "building_id", List.of("ghost_metric"), "", "kWh"),
                new TabularResult(List.of("building_id", "ghost_metric"),
                        List.of(List.of("B1", "7")), false, 1),
                java.util.Map.of("other_column", "kWh"));

        assertThat(spec.type()).isEqualTo(ChartSpec.ChartType.TABLE);
    }
}
