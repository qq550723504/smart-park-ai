package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Guards the conclusion stage: every number the model mentions must be
 * traceable to executed result values or row/column counts. Unsupported
 * numbers are rejected — the SQL and result table survive without a conclusion.
 */
public class AnalysisSummaryValidator {

    // Do not extract digits from ASCII identifiers such as B1/MTR-2. Chinese
    // text is prose, not an identifier boundary: ordinary output such as
    // “能耗为9999kWh” must still expose 9999 to the grounding check.
    // A unit suffix is intentionally allowed (10kWh), and scientific notation
    // / Unicode minus are normalized before comparison with result cells.
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9_\\-−－‐‑‒–—―﹣])[\\-−－‐‑‒–—―﹣]?"
                    + "(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
                    + "(?:[eE][+\\-−－‐‑‒–—―﹣]?[0-9]+)?");

    /** Comparative or trend claims cannot be verified from a static result table; they are refused. */
    private static final List<String> UNVERIFIABLE_CLAIMS = List.of(
            "上升", "下降", "增长", "降低", "增加", "减少", "最高", "最低", "趋势",
            "increase", "decrease", "highest", "lowest", "rising", "falling", "trend");

    /** Qualitative predicates are not facts in a tabular result and must not be invented by the model. */
    private static final List<String> UNSUPPORTED_QUALITATIVE_CLAIMS = List.of(
            "异常", "正常", "偏高", "偏低", "严重", "安全", "不安全", "anomaly", "abnormal", "unsafe");

    public String validate(String conclusion, QueryPlan plan, TabularResult result) {
        if (conclusion == null || conclusion.isBlank()) {
            throw new IllegalArgumentException("结论不能为空");
        }
        String lowered = conclusion.toLowerCase(java.util.Locale.ROOT);
        for (String claim : UNVERIFIABLE_CLAIMS) {
            if (lowered.contains(claim)) {
                throw new IllegalArgumentException("结论包含无法从结果表验证的趋势性描述: " + claim);
            }
        }
        for (String claim : UNSUPPORTED_QUALITATIVE_CLAIMS) {
            if (lowered.contains(claim)) {
                throw new IllegalArgumentException("结论包含无法从结果表验证的定性描述: " + claim);
            }
        }
        List<String> supported = supportedFigures(result);
        java.util.regex.Matcher matcher = NUMBER.matcher(conclusion);
        List<String> unsupported = new ArrayList<>();
        boolean hasFigure = false;
        while (matcher.find()) {
            hasFigure = true;
            String figure = normalize(matcher.group());
            if (!supported.contains(figure)) {
                unsupported.add(figure);
            }
        }
        if (!hasFigure) {
            throw new IllegalArgumentException("结论缺少可验证的结果数值");
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("结论包含结果数据不支持的数字: " + unsupported);
        }
        validateDimensionFigureRelationships(conclusion, plan, result);
        return conclusion.strip();
    }

    private void validateDimensionFigureRelationships(String conclusion,
                                                      QueryPlan plan,
                                                      TabularResult result) {
        List<RowFact> rowFacts = rowFacts(plan, result);
        if (rowFacts.isEmpty()) return;
        Set<String> knownDimensions = rowFacts.stream()
                .flatMap(row -> row.dimensionValues().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean dimensionlessResult = rowFacts.stream().allMatch(row -> row.dimensionValues().isEmpty());
        List<Mention> mentions = new ArrayList<>();
        for (String dimension : knownDimensions) {
            if (NUMBER.matcher(dimension).matches()) continue;
            java.util.regex.Matcher matcher = dimensionPattern(dimension).matcher(conclusion.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                mentions.add(new Mention(matcher.start(), matcher.end(), dimension, true, false));
            }
        }
        java.util.regex.Matcher figureMatcher = NUMBER.matcher(conclusion);
        while (figureMatcher.find()) {
            String figure = normalize(figureMatcher.group());
            mentions.add(new Mention(figureMatcher.start(), figureMatcher.end(), figure,
                    knownDimensions.contains(figure), true));
        }
        mentions.sort(java.util.Comparator.comparingInt(Mention::start)
                .thenComparing(mention -> mention.numeric() ? 1 : 0));

        Set<String> dimensions = new LinkedHashSet<>();
        Set<String> figures = new LinkedHashSet<>();
        for (Mention mention : mentions) {
            if (!mention.numeric()) {
                if (!figures.isEmpty()) {
                    validateRowGroup(dimensions, figures, rowFacts, plan, conclusion);
                    dimensions.clear();
                    figures.clear();
                }
                dimensions.add(mention.value());
                continue;
            }

            if (mention.dimensionCandidate() && (dimensions.isEmpty() || !figures.isEmpty())) {
                if (!figures.isEmpty()) {
                    validateRowGroup(dimensions, figures, rowFacts, plan, conclusion);
                    dimensions.clear();
                    figures.clear();
                }
                dimensions.add(mention.value());
                continue;
            }
            if (isMetadataFigure(conclusion, mention, result)) {
                continue;
            }
            if (dimensions.isEmpty() && !dimensionlessResult) {
                throw new IllegalArgumentException("结论中的数字缺少可验证的维度对应关系: " + mention.value());
            }
            figures.add(mention.value());
        }
        if (!figures.isEmpty()) validateRowGroup(dimensions, figures, rowFacts, plan, conclusion);
    }

    private void validateRowGroup(Set<String> dimensions, Set<String> figures, List<RowFact> rowFacts,
                                  QueryPlan plan, String conclusion) {
        boolean supportedByOneRow = rowFacts.stream().anyMatch(row ->
                row.dimensionValues().containsAll(dimensions)
                        && figures.stream().allMatch(figure -> figureMatchesRow(
                        figure, row, plan, conclusion)));
        if (!supportedByOneRow) {
            throw new IllegalArgumentException(
                    "结论中的实体与数字对应关系不受结果行支持: " + dimensions + " -> " + figures);
        }
    }

    private boolean figureMatchesRow(String figure, RowFact row, QueryPlan plan, String conclusion) {
        Set<String> columns = row.columnsForFigure(figure);
        if (columns.isEmpty()) return false;
        for (Mention mention : numericMentions(conclusion, figure)) {
            Set<String> expectedColumns = expectedMetricColumns(mention, row, plan, conclusion);
            if (!expectedColumns.isEmpty() && java.util.Collections.disjoint(columns, expectedColumns)) {
                return false;
            }
            if (expectedColumns.isEmpty() && columns.size() > 1) {
                // A value repeated in multiple metric columns is not safely
                // attributable without an explicit metric/unit claim.
                return false;
            }
        }
        return true;
    }

    private Set<String> expectedMetricColumns(Mention mention, RowFact row,
                                               QueryPlan plan, String conclusion) {
        int localStart = Math.max(0, mention.start() - 3);
        int localEnd = Math.min(conclusion.length(), mention.end() + 5);
        String localWindow = conclusion.substring(localStart, localEnd).toLowerCase(Locale.ROOT);
        List<com.example.smartpark.analytics.catalog.MetricDefinition> unitHinted = plan.metrics().stream()
                .filter(metric -> metric.unit() != null && !metric.unit().isBlank()
                        && localWindow.contains(metric.unit().toLowerCase(Locale.ROOT)))
                .toList();
        List<com.example.smartpark.analytics.catalog.MetricDefinition> hinted = unitHinted.isEmpty()
                ? plan.metrics().stream()
                .filter(metric -> mentionsMetric(conclusion, mention, metric))
                .toList()
                : unitHinted;
        if (hinted.isEmpty()) return Set.of();
        Set<String> expected = new LinkedHashSet<>();
        for (var metric : hinted) {
            row.figuresByColumn().keySet().stream()
                    .filter(column -> column.equalsIgnoreCase(metric.name()))
                    .findFirst()
                    .ifPresent(expected::add);
        }
        if (expected.isEmpty() && plan.metrics().size() == 1) {
            expected.addAll(row.numericColumns());
        }
        return expected;
    }

    private boolean mentionsMetric(String conclusion, Mention mention,
                                   com.example.smartpark.analytics.catalog.MetricDefinition metric) {
        int start = Math.max(0, mention.start() - 32);
        int end = Math.min(conclusion.length(), mention.end() + 32);
        String window = conclusion.substring(start, end).toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(metric.name(), metric.displayName(), metric.unit()),
                        metric.aliases().stream())
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.toLowerCase(Locale.ROOT))
                .anyMatch(window::contains);
    }

    private List<Mention> numericMentions(String conclusion, String figure) {
        List<Mention> mentions = new ArrayList<>();
        java.util.regex.Matcher matcher = NUMBER.matcher(conclusion);
        while (matcher.find()) {
            if (normalize(matcher.group()).equals(figure)) {
                mentions.add(new Mention(matcher.start(), matcher.end(), figure, false, true));
            }
        }
        return mentions;
    }

    private boolean isMetadataFigure(String conclusion, Mention figure, TabularResult result) {
        int segmentStart = metadataSegmentStart(conclusion, figure.start());
        int segmentEnd = metadataSegmentEnd(conclusion, figure.end());
        String prefix = conclusion.substring(segmentStart, figure.start()).strip();
        String suffix = conclusion.substring(figure.end(), segmentEnd).strip();
        // Metadata is a tiny explicit language contract, not an inference from
        // the absence of a known entity. This prevents unknown entities and
        // cross-sentence pronouns from disguising a data figure as row count.
        if (!prefix.matches("(?:共返回|共计|共)")) return false;
        boolean rowCount = figure.value().equals(String.valueOf(result.rowCount()))
                && suffix.matches("(?:个)?行(?:数据|结果)?");
        boolean columnCount = figure.value().equals(String.valueOf(result.columnNames().size()))
                && suffix.matches("(?:个)?列(?:数据|结果)?");
        return rowCount || columnCount;
    }

    private int metadataSegmentStart(String text, int before) {
        for (int index = before - 1; index >= 0; index--) {
            if (isMetadataDelimiter(text, index)) return index + 1;
        }
        return 0;
    }

    private int metadataSegmentEnd(String text, int after) {
        for (int index = after; index < text.length(); index++) {
            if (isMetadataDelimiter(text, index)) return index;
        }
        return text.length();
    }

    private boolean isMetadataDelimiter(String text, int index) {
        char value = text.charAt(index);
        if ("，,。！？!?;；\n\r".indexOf(value) >= 0) return true;
        if (value != '.') return false;
        boolean decimalPoint = index > 0 && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
        return !decimalPoint;
    }

    private List<RowFact> rowFacts(QueryPlan plan, TabularResult result) {
        List<Integer> dimensionIndexes = new ArrayList<>();
        for (String dimension : plan.dimensions()) {
            int index = -1;
            for (int candidate = 0; candidate < result.columnNames().size(); candidate++) {
                if (result.columnNames().get(candidate).equalsIgnoreCase(dimension)) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("查询结果缺少计划维度列: " + dimension);
            }
            dimensionIndexes.add(index);
        }
        List<RowFact> facts = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            Set<String> dimensions = new LinkedHashSet<>();
            Map<String, Set<String>> figuresByColumn = new java.util.LinkedHashMap<>();
            for (int index : dimensionIndexes) {
                if (index < row.size() && row.get(index) != null) {
                    String value = row.get(index).toString().strip().toLowerCase(Locale.ROOT);
                    dimensions.add(NUMBER.matcher(value).matches() ? normalize(value) : value);
                }
            }
            for (int index = 0; index < row.size(); index++) {
                if (dimensionIndexes.contains(index)) continue;
                Object value = row.get(index);
                if (value == null) continue;
                if (value instanceof Number number) {
                    figuresByColumn.computeIfAbsent(result.columnNames().get(index), ignored -> new LinkedHashSet<>())
                            .add(normalize(stripTrailingZeros(number)));
                } else if (NUMBER.matcher(value.toString()).matches()) {
                    figuresByColumn.computeIfAbsent(result.columnNames().get(index), ignored -> new LinkedHashSet<>())
                            .add(normalize(value.toString()));
                }
            }
            facts.add(new RowFact(Set.copyOf(dimensions), figuresByColumn.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> Set.copyOf(entry.getValue())))));
        }
        return List.copyOf(facts);
    }

    private java.util.regex.Pattern dimensionPattern(String normalizedValue) {
        if (normalizedValue.matches("[a-z0-9_-]+")) {
            return java.util.regex.Pattern.compile(
                    "(?<![a-z0-9_-])" + java.util.regex.Pattern.quote(normalizedValue)
                            + "(?![a-z0-9_-])");
        }
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(normalizedValue));
    }

    private record RowFact(Set<String> dimensionValues, Map<String, Set<String>> figuresByColumn) {
        Set<String> figures() {
            return figuresByColumn.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
        }

        Set<String> columnsForFigure(String figure) {
            return figuresByColumn.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(figure))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
        }

        Set<String> numericColumns() { return figuresByColumn.keySet(); }
    }

    private record Mention(int start, int end, String value,
                           boolean dimensionCandidate, boolean numeric) { }

    private List<String> supportedFigures(TabularResult result) {
        List<String> figures = new ArrayList<>();
        figures.add(String.valueOf(result.rowCount()));
        figures.add(String.valueOf(result.columnNames().size()));
        for (List<Object> row : result.rows()) {
            for (Object value : row) {
                if (value instanceof Number number) {
                    figures.add(normalize(stripTrailingZeros(number)));
                } else if (value != null && NUMBER.matcher(value.toString()).matches()) {
                    figures.add(normalize(value.toString()));
                }
            }
        }
        return figures;
    }

    private static String stripTrailingZeros(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            return normalize(d == Math.floor(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d)
                    : String.valueOf(d));
        }
        return normalize(number.toString());
    }

    private static String normalize(String raw) {
        // "1820.50", "1.8205e3" and Unicode-minus spellings describe the
        // same result figure and therefore share one canonical decimal form.
        String normalized = raw.replaceAll("[\\-−－‐‑‒–—―﹣]", "-");
        try {
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }
}
