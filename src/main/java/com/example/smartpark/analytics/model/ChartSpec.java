package com.example.smartpark.analytics.model;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated display specification over an executed result. Chart fields and
 * units are never accepted without checking the real result and the plan.
 */
public record ChartSpec(
        ChartType type,
        String title,
        String xField,
        List<String> yFields,
        String seriesField,
        String unit,
        RenderOptions options) {

    public enum ChartType {
        LINE, BAR, TABLE, KPI, STACKED_BAR, HEATMAP, CALENDAR_HEATMAP, SCATTER, GAUGE, MAP
    }

    public record RenderOptions(
            String orientation,
            boolean stacked,
            Double targetValue,
            String coordinateXField,
            String coordinateYField) {

        public RenderOptions {
            orientation = orientation == null || orientation.isBlank()
                    ? "VERTICAL" : orientation.strip().toUpperCase(Locale.ROOT);
            if (!Set.of("VERTICAL", "HORIZONTAL").contains(orientation)) {
                throw new IllegalArgumentException("unsupported chart orientation: " + orientation);
            }
            if (targetValue != null && (!Double.isFinite(targetValue) || targetValue < 0)) {
                throw new IllegalArgumentException("targetValue must be finite and non-negative");
            }
            coordinateXField = coordinateXField == null ? "" : coordinateXField.strip();
            coordinateYField = coordinateYField == null ? "" : coordinateYField.strip();
        }

        public static RenderOptions defaults() {
            return new RenderOptions("VERTICAL", false, null, "", "");
        }
    }

    public ChartSpec(ChartType type, String title, String xField, List<String> yFields,
                     String seriesField, String unit) {
        this(type, title, xField, yFields, seriesField, unit, RenderOptions.defaults());
    }

    public ChartSpec {
        Objects.requireNonNull(type, "type");
        title = Objects.requireNonNull(title, "title");
        xField = Objects.requireNonNull(xField, "xField");
        yFields = List.copyOf(Objects.requireNonNullElse(yFields, List.of()));
        seriesField = Objects.requireNonNull(seriesField, "seriesField");
        unit = Objects.requireNonNull(unit, "unit");
        options = Objects.requireNonNullElse(options, RenderOptions.defaults());
        if (type != ChartType.TABLE && yFields.isEmpty()) {
            throw new IllegalArgumentException(type + " requires at least one yField");
        }
    }

    public record Proposal(String type, String title, String xField, List<String> yFields,
                           String seriesField, String unit, RenderOptions options) {
        public Proposal(String type, String title, String xField, List<String> yFields,
                        String seriesField, String unit) {
            this(type, title, xField, yFields, seriesField, unit, RenderOptions.defaults());
        }
    }

    public static ChartSpec fromProposal(Proposal proposal, TabularResult result) {
        return fromProposal(proposal, result, Map.of());
    }

    /**
     * Validates a model proposal against real result columns and planned
     * metric units. Every invalid shape degrades to a table over the same
     * result; it never fabricates values or columns.
     */
    public static ChartSpec fromProposal(Proposal proposal, TabularResult result,
                                         Map<String, String> unitByColumn) {
        Objects.requireNonNull(result, "result");
        Map<String, String> units = Objects.requireNonNullElse(unitByColumn, Map.of());
        String type = proposal == null || proposal.type() == null ? "" : proposal.type().strip();
        try {
            ChartType chartType = ChartType.valueOf(type.toUpperCase(Locale.ROOT));
            return build(chartType, proposal.title(), proposal.xField(), proposal.yFields(),
                    proposal.seriesField(), proposal.unit(), proposal.options(), result, units);
        } catch (RuntimeException invalidProposal) {
            return tableFallback(proposal == null ? null : proposal.title(), result);
        }
    }

    /**
     * Deterministic last-mile selector for the supported visualization
     * intents. It only proposes fields present in the executed result; the
     * same validator used for model proposals remains the final authority.
     */
    public static ChartSpec recommended(String question, TabularResult result,
                                       Map<String, String> unitByColumn) {
        Objects.requireNonNull(result, "result");
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String y = firstExisting(result, "energy_target_completion_pct", "energy_kwh", "night_energy_kwh",
                "peak_kw", "alert_count", "high_risk_alert_count", "parking_entries", "device_offline_count");
        if (y == null) y = firstNumeric(result, unitByColumn);
        if (y == null) return tableFallback("查询结果", result);

        String title = question == null || question.isBlank() ? "查询结果" : question.strip();
        if (containsAny(text, "目标完成率", "目标达成率") && result.rows().size() == 1) {
            return fromProposal(new Proposal("GAUGE", title, y, List.of(y), "", ""), result, unitByColumn);
        }

        String coordinateX = firstExisting(result, "map_x");
        String coordinateY = firstExisting(result, "map_y");
        if (containsAny(text, "空间分布", "地图", "平面图", "位置分布")
                && coordinateX != null && coordinateY != null) {
            String x = firstExisting(result, "building_name", "building_id");
            if (x != null) {
                return fromProposal(new Proposal("MAP", title, x, List.of(y), "", "",
                        new RenderOptions("VERTICAL", false, null, coordinateX, coordinateY)),
                        result, unitByColumn);
            }
        }

        if (containsAny(text, "日历热力图", "日历")) {
            String x = firstExisting(result, "stat_date");
            if (x != null) {
                return fromProposal(new Proposal("CALENDAR_HEATMAP", title, x, List.of(y), "", ""),
                        result, unitByColumn);
            }
        }

        if (containsAny(text, "热力图", "热力")) {
            String x = firstExisting(result, "stat_date", "hour_ts", "hour_of_day");
            String series = firstExisting(result, "building_id", "building_name", "hour_of_day", "day_of_week");
            if (x != null && series != null && !x.equals(series)) {
                return fromProposal(new Proposal("HEATMAP", title, x, List.of(y), series, ""),
                        result, unitByColumn);
            }
        }

        if (containsAny(text, "关系", "相关性", "散点")) {
            String x = firstExisting(result, "occupancy_avg");
            if (x != null && numericField(x, result) && !x.equals(y)) {
                return fromProposal(new Proposal("SCATTER", title, x, List.of(y), "", ""),
                        result, unitByColumn);
            }
        }

        if (containsAny(text, "构成", "堆叠", "分时")) {
            String x = firstExisting(result, "building_name", "building_id");
            String series = firstExisting(result, "hour_of_day", "day_of_week");
            if (x != null && series != null) {
                return fromProposal(new Proposal("STACKED_BAR", title, x, List.of(y), series, "",
                        new RenderOptions("VERTICAL", true, null, "", "")), result, unitByColumn);
            }
        }

        String x = firstExisting(result, "building_name", "building_id", "category", "device_type",
                "parking_zone", "risk_level", "status", "meter_id", "day_of_week",
                "stat_date", "hour_ts", "hour_of_day");
        if (x != null) {
            String type = containsAny(text, "趋势", "按小时", "逐时", "按日", "每日", "按日期") ? "LINE" : "BAR";
            RenderOptions options = containsAny(text, "排行", "排名")
                    ? new RenderOptions("HORIZONTAL", false, null, "", "")
                    : RenderOptions.defaults();
            // A grouped result with two categorical dimensions must not collapse
            // to a single series: a bare BAR would overwrite repeated x positions
            // and silently drop all but the last value of the other dimension.
            // The second dimension becomes the series so every grouping column
            // stays represented; when the contract validator still cannot express
            // it, the caller keeps the table fallback.
            String series = secondCategoricalDimension(result, x, y);
            return fromProposal(new Proposal(type, title, x, List.of(y), series, "", options),
                    result, unitByColumn);
        }
        if (result.rows().size() == 1 && containsAny(text, "总量", "总数", "数量", "完成率")) {
            return fromProposal(new Proposal("KPI", title, y, List.of(y), "", ""), result, unitByColumn);
        }
        return tableFallback(title, result);
    }

    public static boolean hasVisualizationIntent(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return containsAny(text, "趋势", "排行", "排名", "热力图", "热力", "日历", "关系", "相关性",
                "散点", "目标完成率", "目标达成率", "空间分布", "分布", "地图", "平面图", "位置分布", "构成", "堆叠",
                "分时", "总量", "总数");
    }

    private static String firstExisting(TabularResult result, String... fields) {
        for (String field : fields) if (result.columnNames().contains(field)) return field;
        return null;
    }

    /**
     * Second categorical grouping column of a two-dimensional result, or null
     * when every remaining column is the metric or an unrepresentable field.
     * Known categorical axes only: inventing a series from numeric or coordinate
     * columns would produce meaningless stacks.
     */
    private static String secondCategoricalDimension(TabularResult result, String x, String y) {
        List<String> categorical = List.of("building_name", "building_id", "category", "device_type",
                "parking_zone", "risk_level", "status", "meter_id", "day_of_week", "hour_of_day");
        for (String field : categorical) {
            if (field.equals(x) || field.equals(y)) continue;
            if (result.columnNames().contains(field)) return field;
        }
        return null;
    }

    private static String firstNumeric(TabularResult result, Map<String, String> unitByColumn) {
        for (String field : result.columnNames()) {
            if (unitByColumn.containsKey(field.toLowerCase(Locale.ROOT)) && numericField(field, result)) return field;
        }
        return null;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static ChartSpec build(ChartType type, String title, String xField, List<String> yFields,
                                   String seriesField, String proposedUnit, RenderOptions proposedOptions,
                                   TabularResult result, Map<String, String> unitByColumn) {
        if (type == ChartType.TABLE || title == null || title.isBlank()
                || xField == null || !result.columnNames().contains(xField)
                || yFields == null || yFields.isEmpty()
                || !result.columnNames().containsAll(yFields)
                || !numericYFields(yFields, result)
                || (seriesField != null && !seriesField.isBlank() && !result.columnNames().contains(seriesField))) {
            throw new IllegalArgumentException("chart proposal references unknown or non-numeric result columns");
        }

        RenderOptions options = Objects.requireNonNullElse(proposedOptions, RenderOptions.defaults());
        String resolvedSeries = seriesField == null || seriesField.isBlank() ? "-" : seriesField;
        switch (type) {
            case STACKED_BAR, HEATMAP -> {
                if ("-".equals(resolvedSeries)) {
                    throw new IllegalArgumentException(type + " requires a series field");
                }
            }
            case SCATTER -> {
                if (!numericField(xField, result) || yFields.size() != 1) {
                    throw new IllegalArgumentException("scatter requires one numeric x and y field");
                }
            }
            case KPI, GAUGE -> {
                if (yFields.size() != 1 || result.rows().size() != 1) {
                    throw new IllegalArgumentException(type + " requires one numeric value");
                }
            }
            case MAP -> {
                String coordinateX = options.coordinateXField().isBlank() ? "map_x" : options.coordinateXField();
                String coordinateY = options.coordinateYField().isBlank() ? "map_y" : options.coordinateYField();
                if (!result.columnNames().contains(coordinateX) || !result.columnNames().contains(coordinateY)
                        || !numericField(coordinateX, result) || !numericField(coordinateY, result)) {
                    throw new IllegalArgumentException("map requires numeric coordinate fields");
                }
                options = new RenderOptions(options.orientation(), options.stacked(), options.targetValue(),
                        coordinateX, coordinateY);
            }
            default -> { }
        }
        requireUniqueCoordinates(type, xField, resolvedSeries, result, options);
        Double target = options.targetValue();
        if (type == ChartType.GAUGE) target = 100.0;
        options = new RenderOptions(options.orientation(), type == ChartType.STACKED_BAR || options.stacked(),
                target, options.coordinateXField(), options.coordinateYField());
        return new ChartSpec(type, title.strip(), xField, yFields, resolvedSeries,
                resolveUnit(type, yFields, proposedUnit, unitByColumn), options);
    }

    private static void requireUniqueCoordinates(ChartType type, String xField, String seriesField,
                                                 TabularResult result, RenderOptions options) {
        if (type == ChartType.TABLE || type == ChartType.KPI || type == ChartType.GAUGE) return;
        int xIndex = result.columnNames().indexOf(xField);
        int seriesIndex = "-".equals(seriesField) ? -1 : result.columnNames().indexOf(seriesField);
        if (type == ChartType.MAP) {
            xIndex = result.columnNames().indexOf(options.coordinateXField());
            seriesIndex = result.columnNames().indexOf(options.coordinateYField());
        }
        Set<String> seen = new HashSet<>();
        for (List<Object> row : result.rows()) {
            String key = String.valueOf(row.get(xIndex)) + "\u0000"
                    + (seriesIndex < 0 ? "" : String.valueOf(row.get(seriesIndex)));
            if (!seen.add(key)) throw new IllegalArgumentException("duplicate chart coordinate");
        }
    }

    private static boolean numericYFields(List<String> yFields, TabularResult result) {
        return yFields.stream().allMatch(field -> numericField(field, result));
    }

    private static boolean numericField(String field, TabularResult result) {
        int index = result.columnNames().indexOf(field);
        if (index < 0 || result.rows().isEmpty()) return false;
        boolean hasValue = false;
        for (List<Object> row : result.rows()) {
            Object value = row.get(index);
            if (value == null) continue;
            hasValue = true;
            if (value instanceof Number number) {
                if (!Double.isFinite(number.doubleValue())) return false;
            } else {
                try {
                    if (!Double.isFinite(Double.parseDouble(value.toString().strip()))) return false;
                } catch (NumberFormatException invalidNumber) {
                    return false;
                }
            }
        }
        return hasValue;
    }

    private static String resolveUnit(ChartType type, List<String> yFields, String proposedUnit,
                                      Map<String, String> unitByColumn) {
        if (type == ChartType.TABLE || unitByColumn.isEmpty()) return proposedUnit == null ? "" : proposedUnit;
        Set<String> units = new LinkedHashSet<>();
        for (String field : yFields) {
            String unit = unitByColumn.get(field.toLowerCase(Locale.ROOT));
            if (unit == null || unit.isBlank()) throw new IllegalArgumentException("chart y-field has no planned metric unit");
            units.add(unit);
        }
        if (units.size() != 1) throw new IllegalArgumentException("chart mixes metrics of different units");
        return units.iterator().next();
    }

    private static ChartSpec tableFallback(String title, TabularResult result) {
        String first = result.columnNames().isEmpty() ? "-" : result.columnNames().get(0);
        return new ChartSpec(ChartType.TABLE, title == null || title.isBlank() ? "查询结果" : title.strip(),
                first, List.of(), "-", "", RenderOptions.defaults());
    }
}
