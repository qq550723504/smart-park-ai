package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guards the conclusion stage: every number the model mentions must be
 * traceable to executed result values or row/column counts. Unsupported
 * numbers are rejected — the SQL and result table survive without a conclusion.
 */
public class AnalysisSummaryValidator {

    // Digits inside identifiers (B1, MTR-2) are not figures; require non-alphanumeric context.
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])-?[0-9]+(?:\\.[0-9]+)?(?![0-9A-Za-z])");

    /** Comparative or trend claims cannot be verified from a static result table; they are refused. */
    private static final List<String> UNVERIFIABLE_CLAIMS = List.of(
            "上升", "下降", "增长", "降低", "增加", "减少", "最高", "最低", "趋势",
            "increase", "decrease", "highest", "lowest", "rising", "falling", "trend");

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
        validateDimensionFigureRelationships(conclusion, plan, result);
        List<String> supported = supportedFigures(result);
        java.util.regex.Matcher matcher = NUMBER.matcher(conclusion);
        List<String> unsupported = new ArrayList<>();
        while (matcher.find()) {
            String figure = normalize(matcher.group());
            if (!supported.contains(figure)) {
                unsupported.add(figure);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("结论包含结果数据不支持的数字: " + unsupported);
        }
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
        List<Mention> mentions = new ArrayList<>();
        for (String dimension : knownDimensions) {
            java.util.regex.Matcher matcher = dimensionPattern(dimension).matcher(conclusion.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                mentions.add(new Mention(matcher.start(), matcher.end(), dimension, true));
            }
        }
        java.util.regex.Matcher figureMatcher = NUMBER.matcher(conclusion);
        while (figureMatcher.find()) {
            boolean overlapsDimension = mentions.stream().anyMatch(mention ->
                    figureMatcher.start() < mention.end() && figureMatcher.end() > mention.start());
            String figure = normalize(figureMatcher.group());
            if (!overlapsDimension && !isMetadataFigure(conclusion, figureMatcher.start(),
                    figureMatcher.end(), figure, result)) {
                mentions.add(new Mention(figureMatcher.start(), figureMatcher.end(), figure, false));
            }
        }
        mentions.sort(java.util.Comparator.comparingInt(Mention::start)
                .thenComparing(mention -> mention.dimension() ? 0 : 1));

        Set<String> dimensions = new LinkedHashSet<>();
        Set<String> figures = new LinkedHashSet<>();
        for (Mention mention : mentions) {
            if (mention.dimension()) {
                if (!figures.isEmpty()) {
                    validateRowGroup(dimensions, figures, rowFacts);
                    dimensions.clear();
                    figures.clear();
                }
                dimensions.add(mention.value());
            } else {
                if (dimensions.isEmpty()) {
                    throw new IllegalArgumentException("结论中的数字缺少可验证的维度对应关系: " + mention.value());
                }
                figures.add(mention.value());
            }
        }
        if (!figures.isEmpty()) validateRowGroup(dimensions, figures, rowFacts);
    }

    private void validateRowGroup(Set<String> dimensions, Set<String> figures, List<RowFact> rowFacts) {
        boolean supportedByOneRow = rowFacts.stream().anyMatch(row ->
                row.dimensionValues().containsAll(dimensions) && row.figures().containsAll(figures));
        if (!supportedByOneRow) {
            throw new IllegalArgumentException(
                    "结论中的实体与数字对应关系不受结果行支持: " + dimensions + " -> " + figures);
        }
    }

    private boolean isMetadataFigure(String conclusion, int start, int end,
                                     String figure, TabularResult result) {
        int contextEnd = Math.min(conclusion.length(), end + 12);
        String context = conclusion.substring(end, contextEnd).toLowerCase(Locale.ROOT);
        boolean rowCount = figure.equals(String.valueOf(result.rowCount()))
                && context.matches("^\\s*(?:个)?(?:行|rows?\\b).*");
        boolean columnCount = figure.equals(String.valueOf(result.columnNames().size()))
                && context.matches("^\\s*(?:个)?(?:列|columns?\\b).*");
        return rowCount || columnCount;
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
        if (dimensionIndexes.isEmpty()) return List.of();

        List<RowFact> facts = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            Set<String> dimensions = new LinkedHashSet<>();
            Set<String> figures = new LinkedHashSet<>();
            for (int index : dimensionIndexes) {
                if (index < row.size() && row.get(index) != null) {
                    dimensions.add(row.get(index).toString().strip().toLowerCase(Locale.ROOT));
                }
            }
            for (int index = 0; index < row.size(); index++) {
                if (dimensionIndexes.contains(index)) continue;
                Object value = row.get(index);
                if (value == null) continue;
                if (value instanceof Number number) {
                    figures.add(normalize(stripTrailingZeros(number)));
                } else if (NUMBER.matcher(value.toString()).matches()) {
                    figures.add(normalize(value.toString()));
                }
            }
            facts.add(new RowFact(Set.copyOf(dimensions), Set.copyOf(figures)));
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

    private record RowFact(Set<String> dimensionValues, Set<String> figures) { }

    private record Mention(int start, int end, String value, boolean dimension) { }

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
        // "1820.50" and "1820.5" describe the same figure.
        if (raw.contains(".")) {
            raw = raw.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return raw;
    }
}
