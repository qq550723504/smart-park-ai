package com.example.smartpark.analytics.model;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void nonLineBarChartsRequireAtLeastOneYField() {
        assertThatThrownBy(() -> new ChartSpec(ChartSpec.ChartType.BAR, "t", "x",
                List.of(), "-", ""))
                .isInstanceOf(IllegalArgumentException.class);
        // TABLE may legitimately have no yFields.
        assertThat(new ChartSpec(ChartSpec.ChartType.TABLE, "t", "building_id",
                List.of(), "-", "").yFields()).isEmpty();
    }
}
