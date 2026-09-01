package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.catalog.CategoricalFilterVocabulary;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts model-advisory dimensions and filters into a conservative canonical
 * form. The original question remains authoritative: an LLM cannot add a
 * grouping dimension or entity predicate that the operator did not state.
 */
public final class AnalyticsQuestionNormalizer {

    private static final Map<String, String> DIMENSION_ALIASES = Map.ofEntries(
            Map.entry("building", "building_id"),
            Map.entry("楼宇", "building_id"),
            Map.entry("楼栋", "building_id"),
            Map.entry("建筑", "building_id"),
            Map.entry("building_id", "building_id"),
            Map.entry("building_name", "building_name"),
            Map.entry("楼宇名称", "building_name"),
            Map.entry("meter", "meter_id"),
            Map.entry("表计", "meter_id"),
            Map.entry("电表", "meter_id"),
            Map.entry("meter_id", "meter_id"),
            Map.entry("hour", "hour_ts"),
            Map.entry("小时", "hour_ts"),
            Map.entry("hour_ts", "hour_ts"),
            Map.entry("hour_of_day", "hour_of_day"),
            Map.entry("时段", "hour_of_day"),
            Map.entry("day_of_week", "day_of_week"),
            Map.entry("星期", "day_of_week"),
            Map.entry("area_sqm", "area_sqm"),
            Map.entry("面积", "area_sqm"),
            Map.entry("map_x", "map_x"),
            Map.entry("map_y", "map_y"),
            Map.entry("occurred", "occurred_at"),
            Map.entry("occurred_at", "occurred_at"),
            Map.entry("snapshot", "snapshot_at"),
            Map.entry("snapshot_at", "snapshot_at"),
            Map.entry("date", "stat_date"),
            Map.entry("日期", "stat_date"),
            Map.entry("stat_date", "stat_date"),
            Map.entry("risk", "risk_level"),
            Map.entry("风险", "risk_level"),
            Map.entry("risk_level", "risk_level"),
            Map.entry("category", "category"),
            Map.entry("类别", "category"),
            Map.entry("分类", "category"),
            Map.entry("status", "status"),
            Map.entry("状态", "status"),
            Map.entry("device", "device_type"),
            Map.entry("设备类型", "device_type"),
            Map.entry("device_type", "device_type"),
            Map.entry("parking_zone", "parking_zone"),
            Map.entry("车区", "parking_zone"),
            Map.entry("区域", "parking_zone"));

    public AnalyticsModelClient.QuestionUnderstanding normalize(
            String originalQuestion, AnalyticsModelClient.QuestionUnderstanding understanding) {
        String question = originalQuestion == null ? "" : originalQuestion.strip();
        LinkedHashSet<String> dimensions = new LinkedHashSet<>();
        for (String requested : understanding.requestedDimensions()) {
            String canonical = canonicalDimension(requested);
            if (canonical != null && explicitlyGrouped(canonical, question)) {
                dimensions.add(canonical);
            }
        }

        LinkedHashMap<String, String> filters = new LinkedHashMap<>();
        for (var entry : understanding.requestedFilters().entrySet()) {
            String dimension = canonicalDimension(entry.getKey());
            String value = canonicalFilterValue(dimension, entry.getValue());
            if (dimension != null && value != null && valueAppearsInQuestion(dimension, value, question)) {
                filters.putIfAbsent(dimension, value);
            }
        }
        return new AnalyticsModelClient.QuestionUnderstanding(
                question,
                understanding.metricTerms(),
                understanding.clarificationQuestions(),
                understanding.requestedTimeRange(),
                List.copyOf(dimensions),
                Map.copyOf(filters),
                understanding.requestedTimeMentions(),
                understanding.serverResolvedTimeRange(),
                understanding.serverReferenceInstant());
    }

    private static String canonicalDimension(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.strip().toLowerCase(Locale.ROOT);
        return DIMENSION_ALIASES.getOrDefault(normalized, normalized.matches("[a-z][a-z0-9_]*")
                ? normalized : null);
    }

    private static String canonicalFilterValue(String dimension, String value) {
        return value == null || value.isBlank() ? null
                : CategoricalFilterVocabulary.canonicalValue(dimension, value.strip());
    }

    private static boolean valueAppearsInQuestion(String dimension, String value, String question) {
        if (Set.of("status", "risk_level", "category").contains(dimension)) {
            return CategoricalFilterVocabulary.valueAppearsInQuestion(dimension, value, question);
        }
        return question.contains(value);
    }

    private static boolean explicitlyGrouped(String dimension, String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return switch (dimension) {
            case "building_id" -> containsAny(normalized,
                    "按楼宇", "各楼宇", "每个楼宇", "分楼宇", "楼宇对比", "楼宇排行", "楼宇热力",
                    "楼宇构成", "楼宇分布", "楼宇空间", "按楼栋", "各楼栋", "每栋", "各栋");
            case "building_name" -> containsAny(normalized,
                    "按楼宇", "各楼宇", "每个楼宇", "分楼宇", "楼宇对比", "楼宇排行", "楼宇热力",
                    "楼宇构成", "楼宇名称", "空间分布", "地图");
            case "meter_id" -> containsAny(normalized,
                    "按表计", "各表计", "每个表计", "分表计", "按电表", "各电表");
            case "hour_ts" -> containsAny(normalized,
                    "按小时", "逐时", "每小时", "逐小时", "小时趋势");
            case "hour_of_day" -> containsAny(normalized,
                    "小时热力", "分时", "时段");
            case "day_of_week" -> containsAny(normalized, "按星期", "各星期", "周几", "星期");
            case "stat_date" -> containsAny(normalized, "按日", "每天", "每日", "按日期");
            case "area_sqm" -> containsAny(normalized, "按面积", "单位面积", "面积");
            case "map_x", "map_y" -> containsAny(normalized, "空间分布", "地图", "平面图", "位置分布");
            case "risk_level" -> containsAny(normalized, "按风险", "各风险", "按风险等级");
            case "category" -> containsAny(normalized, "按类别", "各类别", "按分类", "各分类", "按类型", "各类型");
            case "status" -> containsAny(normalized, "按状态", "各状态");
            case "device_type" -> containsAny(normalized, "按设备类型", "各设备类型");
            case "parking_zone" -> containsAny(normalized, "按区域", "各区域", "按车区", "各车区", "各停车区域");
            default -> false;
        };
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
