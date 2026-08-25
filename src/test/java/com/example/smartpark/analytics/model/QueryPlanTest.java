package com.example.smartpark.analytics.model;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryPlanTest {

    private final Instant now = Instant.parse("2026-08-24T08:00:00Z");

    private MetricDefinition metric(String name) {
        return new MetricDefinition(name, name, java.util.Set.of("别名"), "kWh",
                "analytics.v_energy_hourly", java.util.Set.of("building_id", "hour_ts"), "SUM(kwh)", 7);
    }

    @Test
    void acceptsPlanWithinContractBounds() {
        QueryPlan plan = new QueryPlan("B1 上周能耗", List.of(metric("energy_kwh")),
                List.of("building_id"), Map.of("building_id", "B1"),
                new QueryPlan.TimeRange(now.minusSeconds(86400 * 7), now), 200);
        assertThat(plan.limit()).isEqualTo(200);
        assertThat(plan.metrics()).hasSize(1);
    }

    @Test
    void entityFiltersMustUseCatalogDimensionsAndValuesFromTheOriginalQuestion() {
        assertThatThrownBy(() -> new QueryPlan("B1 energy", List.of(metric("energy_kwh")),
                List.of(), Map.of("customer_id", "B1"),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer_id");
        assertThatThrownBy(() -> new QueryPlan("B1 energy", List.of(metric("energy_kwh")),
                List.of(), Map.of("building_id", "B2"),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("original question");
        assertThatThrownBy(() -> new QueryPlan("B1 energy", List.of(metric("energy_kwh")),
                List.of(), Map.of("building_id", "b1"),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("original question");
    }

    @Test
    void rejectsEntityValuesThatDoNotMatchTheirDimensionType() {
        MetricDefinition energy = new MetricDefinition("energy_kwh", "能耗", java.util.Set.of("能耗"), "kWh",
                "analytics.v_energy_hourly", java.util.Set.of("building_id", "meter_id", "hour_ts"),
                "SUM(kwh)", 7);

        assertThatThrownBy(() -> new QueryPlan("B1 能耗", List.of(energy), List.of(),
                Map.of("meter_id", "B1"), new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meter_id");
        assertThatThrownBy(() -> new QueryPlan("B1 能耗", List.of(energy), List.of(),
                Map.of("building_id", "MTR-2"), new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("building_id");
    }

    @Test
    void rejectsAPlanThatDropsAnEntityIdentifierFromTheQuestion() {
        assertThatThrownBy(() -> new QueryPlan("B1 energy", List.of(metric("energy_kwh")),
                List.of(), Map.of(),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("B1");
    }

    @Test
    void limitMustBeBetweenOneAndFiveHundred() {
        assertThatThrownBy(() -> planWithLimit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planWithLimit(501)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOneMetricAndValidTimeOrder() {
        assertThatThrownBy(() -> new QueryPlan("q", List.of(),
                List.of(), Map.of(), new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryPlan("q", List.of(metric("energy_kwh")),
                List.of(), Map.of(), new QueryPlan.TimeRange(now, now.minusSeconds(60)), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryPlan(" ", List.of(metric("energy_kwh")),
                List.of(), Map.of(), new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDimensionsNotApprovedByEverySelectedMetric() {
        MetricDefinition energy = metric("energy_kwh");
        MetricDefinition parking = new MetricDefinition("parking_entries", "parking_entries",
                java.util.Set.of("停车"), "辆", "analytics.v_parking_daily",
                java.util.Set.of("parking_zone", "stat_date"), "SUM(entries)", 7);

        assertThatThrownBy(() -> new QueryPlan("q", List.of(energy, parking),
                List.of("building_id"), Map.of(),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("building_id");
    }

    private void planWithLimit(int limit) {
        new QueryPlan("q", List.of(metric("energy_kwh")), List.of(), Map.of(),
                new QueryPlan.TimeRange(now.minusSeconds(60), now), limit);
    }
}
