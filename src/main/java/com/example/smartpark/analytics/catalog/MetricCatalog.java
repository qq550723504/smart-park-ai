package com.example.smartpark.analytics.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for metric口径. Models must not invent
 * calculations for "夜间" or "利用率"; they can only reference metrics defined here.
 */
public class MetricCatalog {

    private final Map<String, MetricDefinition> byName = new LinkedHashMap<>();
    private final Map<String, String> aliasToName = new LinkedHashMap<>();
    private final Map<String, List<String>> ambiguousAliases = new LinkedHashMap<>();

    public MetricCatalog() {
        register(new MetricDefinition(
                "energy_kwh", "能耗",
                java.util.Set.of("能耗", "用电量", "电量"),
                "kWh", "analytics.v_energy_hourly",
                java.util.Set.of("building_id", "meter_id", "hour_ts"),
                "SUM(kwh)", 7));
        register(new MetricDefinition(
                "night_energy_kwh", "夜间能耗（22:00–06:00）",
                java.util.Set.of("夜间用电量", "夜间能耗", "夜间电量"),
                "kWh", "analytics.v_energy_hourly",
                java.util.Set.of("building_id", "meter_id", "hour_ts"),
                "SUM(kwh)", 7,
                "(EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai') >= 22 OR "
                        + "EXTRACT(HOUR FROM hour_ts AT TIME ZONE 'Asia/Shanghai') < 6)"));
        register(new MetricDefinition(
                "energy_deviation_pct", "能耗基线偏差率",
                java.util.Set.of("能耗偏差", "基线偏差"),
                "%", "analytics.v_energy_hourly",
                java.util.Set.of("building_id", "meter_id", "hour_ts"),
                "ROUND((SUM(kwh) - SUM(baseline_kwh)) * 100.0 / NULLIF(SUM(baseline_kwh), 0), 2)", 7));
        register(new MetricDefinition(
                "alert_count", "告警数量",
                java.util.Set.of("告警数量", "告警数", "告警"),
                "条", "analytics.v_alert_fact",
                java.util.Set.of("building_id", "category", "risk_level", "status", "occurred_at"),
                "COUNT(*)", 7));
        register(new MetricDefinition(
                "high_risk_alert_count", "高风险告警数量",
                java.util.Set.of("高风险告警数", "高风险告警数量", "告警"),
                "条", "analytics.v_alert_fact",
                java.util.Set.of("building_id", "category", "occurred_at"),
                "COUNT(*)", 7, "risk_level = 'HIGH'"));
        register(new MetricDefinition(
                "device_offline_count", "离线设备数量",
                java.util.Set.of("离线设备", "设备离线数"),
                "台", "analytics.v_device_snapshot",
                java.util.Set.of("building_id", "device_type"),
                "COUNT(*)", 1, "status = 'OFFLINE'"));
        register(new MetricDefinition(
                "parking_entries", "停车进场量",
                java.util.Set.of("停车进场量", "进场车辆数"),
                "辆", "analytics.v_parking_daily",
                java.util.Set.of("stat_date", "parking_zone"),
                "SUM(entries)", 7));
        register(new MetricDefinition(
                "parking_utilization_pct", "停车利用率",
                java.util.Set.of("停车利用率", "车位利用率"),
                "%", "analytics.v_parking_daily",
                java.util.Set.of("stat_date", "parking_zone"),
                "AVG(utilization_pct)", 7));
    }

    private void register(MetricDefinition definition) {
        byName.put(definition.name(), definition);
        for (String alias : definition.aliases()) {
            if (aliasToName.containsKey(alias) && !aliasToName.get(alias).equals(definition.name())) {
                // Alias collision: the term becomes a clarification question instead of guessing.
                List<String> names = ambiguousAliases.computeIfAbsent(alias, ignored -> new ArrayList<>());
                names.add(aliasToName.get(alias));
                names.add(definition.name());
                aliasToName.remove(alias);
            } else {
                aliasToName.put(alias, definition.name());
            }
        }
        // Canonical names are always unambiguous.
        ambiguousAliases.remove(definition.name(), definition.name());
    }

    public Optional<MetricDefinition> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<MetricDefinition> all() {
        return List.copyOf(byName.values());
    }

    public MetricResolution resolve(String term) {
        MetricDefinition direct = byName.get(term);
        if (direct != null) {
            return new MetricResolution.Resolved(direct);
        }
        String mapped = aliasToName.get(term);
        if (mapped != null) {
            return new MetricResolution.Resolved(byName.get(mapped));
        }
        List<String> candidates = ambiguousAliases.get(term);
        if (candidates != null) {
            return new MetricResolution.Ambiguous(term,
                    candidates.stream().map(byName::get).distinct().toList());
        }
        return new MetricResolution.Unknown();
    }
}
