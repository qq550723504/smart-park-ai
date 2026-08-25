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
    void nonLineBarChartsRequireAtLeastOneYField() {
        assertThatThrownBy(() -> new ChartSpec(ChartSpec.ChartType.BAR, "t", "x",
                List.of(), "-", ""))
                .isInstanceOf(IllegalArgumentException.class);
        // TABLE may legitimately have no yFields.
        assertThat(new ChartSpec(ChartSpec.ChartType.TABLE, "t", "building_id",
                List.of(), "-", "").yFields()).isEmpty();
    }
}
