package com.example.smartpark.analytics.model;

import java.util.List;
import java.util.Objects;

/**
 * Frozen chart contract: LINE | BAR | TABLE over real result columns only.
 * Built exclusively through {@link #fromProposal} so fields are always
 * validated against the executed TabularResult.
 */
public record ChartSpec(
        ChartType type,
        String title,
        String xField,
        List<String> yFields,
        String seriesField,
        String unit) {

    public enum ChartType { LINE, BAR, TABLE }

    public ChartSpec {
        Objects.requireNonNull(type, "type");
        title = Objects.requireNonNull(title, "title");
        xField = Objects.requireNonNull(xField, "xField");
        yFields = List.copyOf(Objects.requireNonNullElse(yFields, List.of()));
        if (type != ChartType.TABLE && yFields.isEmpty()) {
            throw new IllegalArgumentException(type + " requires at least one yField");
        }
        seriesField = Objects.requireNonNull(seriesField, "seriesField");
        unit = Objects.requireNonNull(unit, "unit");
    }

    public record Proposal(String type, String title, String xField, List<String> yFields,
                           String seriesField, String unit) {}

    /**
     * Validates the model's chart proposal against real result columns. Any
     * mismatch degrades gracefully to a plain TABLE of the same columns —
     * never to a fabricated chart.
     */
    public static ChartSpec fromProposal(Proposal proposal, TabularResult result) {
        Objects.requireNonNull(result, "result");
        String type = proposal == null || proposal.type() == null ? "" : proposal.type().trim();
        try {
            ChartType chartType = ChartType.valueOf(type.toUpperCase(java.util.Locale.ROOT));
            return build(chartType, proposal.title(), proposal.xField(), proposal.yFields(),
                    proposal.seriesField(), proposal.unit(), result);
        } catch (IllegalArgumentException invalidTypeOrShape) {
            return tableFallback(proposal == null ? null : proposal.title(), result);
        }
    }

    private static ChartSpec build(ChartType type, String title, String xField, List<String> yFields,
                                   String seriesField, String unit, TabularResult result) {
        if (title == null || title.isBlank()
                || xField == null || !result.columnNames().contains(xField)
                || yFields == null || yFields.isEmpty()
                || !result.columnNames().containsAll(yFields)
                || (seriesField != null && !seriesField.isBlank() && !result.columnNames().contains(seriesField))) {
            throw new IllegalArgumentException("chart proposal references unknown result columns");
        }
        String resolvedSeries = seriesField == null || seriesField.isBlank() ? "-" : seriesField;
        return new ChartSpec(type, title.strip(), xField, yFields, resolvedSeries, unit == null ? "" : unit);
    }

    private static ChartSpec tableFallback(String title, TabularResult result) {
        List<String> columns = result.columnNames();
        String first = columns.isEmpty() ? "-" : columns.get(0);
        return new ChartSpec(ChartType.TABLE,
                title == null || title.isBlank() ? "查询结果" : title.strip(),
                first, List.of(), "-", "");
    }
}
