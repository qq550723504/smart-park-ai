package com.example.smartpark.analytics.catalog;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricCatalogTest {

    private final MetricCatalog catalog = new MetricCatalog();

    @Test
    void exposesTheEightContractMetricsWithUnits() {
        assertThat(catalog.all()).extracting(def -> def.name()).containsExactlyInAnyOrder(
                "energy_kwh", "night_energy_kwh", "energy_deviation_pct",
                "alert_count", "high_risk_alert_count",
                "device_offline_count", "parking_entries", "parking_utilization_pct");
        assertThat(catalog.findByName("energy_kwh").orElseThrow().unit()).isEqualTo("kWh");
        assertThat(catalog.findByName("energy_deviation_pct").orElseThrow().unit()).isEqualTo("%");
        assertThat(catalog.findByName("alert_count").orElseThrow().sourceView()).isEqualTo("analytics.v_alert_fact");
        assertThat(catalog.findByName("device_offline_count").orElseThrow().sourceView()).isEqualTo("analytics.v_device_snapshot");
        assertThat(catalog.findByName("parking_utilization_pct").orElseThrow().sourceView()).isEqualTo("analytics.v_parking_daily");
    }

    @Test
    void nightEnergyDefinitionIsFixedTo22To6() {
        var night = catalog.findByName("night_energy_kwh").orElseThrow();
        assertThat(night.condition()).contains("22");
        assertThat(night.condition()).contains("6");
        // Night hours are evaluated in the park timezone, not the session timezone.
        assertThat(night.condition()).contains("EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai')");
    }

    @Test
    void resolvesChineseAliases() {
        assertThat(catalog.resolve("能耗")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("energy_kwh").orElseThrow()));
        assertThat(catalog.resolve("夜间用电量")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("night_energy_kwh").orElseThrow()));
        assertThat(catalog.resolve("告警数量")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("alert_count").orElseThrow()));
        assertThat(catalog.resolve("高风险告警数")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("high_risk_alert_count").orElseThrow()));
        assertThat(catalog.resolve("离线设备")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("device_offline_count").orElseThrow()));
        assertThat(catalog.resolve("停车进场量")).isEqualTo(new MetricResolution.Resolved(catalog.findByName("parking_entries").orElseThrow()));
    }

    @Test
    void ambiguousAliasReturnsClarificationCandidatesInsteadOfGuessing() {
        MetricResolution resolution = catalog.resolve("告警");
        assertThat(resolution).isInstanceOf(MetricResolution.Ambiguous.class);
        assertThat(((MetricResolution.Ambiguous) resolution).candidates())
                .extracting(def -> def.name())
                .containsExactlyInAnyOrder("alert_count", "high_risk_alert_count");
    }

    @Test
    void unknownTermIsNotResolved() {
        assertThat(catalog.resolve("客户满意度")).isEqualTo(new MetricResolution.Unknown());
        assertThatThrownBy(() -> new MetricDefinition(
                "", "", Set.of(), "", "", Set.of(), "", 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyMetricHasAllowedDimensionsAndDefaultRange() {
        for (var definition : catalog.all()) {
            assertThat(definition.allowedDimensions()).isNotEmpty();
            assertThat(definition.defaultLookbackDays()).isBetween(1, 90);
            assertThat(definition.timeColumn()).isIn(definition.allowedDimensions());
            assertThat(definition.sourceView()).startsWith("analytics.v_");
            assertThat(definition.expression()).doesNotContainIgnoringCase("DROP", "INSERT", "UPDATE", "DELETE");
        }
    }
}
