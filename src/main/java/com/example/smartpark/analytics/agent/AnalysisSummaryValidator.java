package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    // Digits inside identifiers (B1, MTR-2) are not figures; require non-alphanumeric context.
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])-?[0-9]+(?:\\.[0-9]+)?(?![0-9A-Za-z])");

    /** Identifiers that contain a digit (B2, MTR-1) name real entities and must exist in the result. */
    private static final java.util.regex.Pattern DIGIT_IDENTIFIER = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]*[0-9][A-Za-z0-9-]*");

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
        List<String> supportedValues = supportedValues(result);
        java.util.regex.Matcher identifiers = DIGIT_IDENTIFIER.matcher(conclusion);
        List<String> unknownEntities = new ArrayList<>();
        while (identifiers.find()) {
            String entity = identifiers.group();
            if (supportedValues.stream().noneMatch(value -> value.contains(entity))) {
                unknownEntities.add(entity);
            }
        }
        if (!unknownEntities.isEmpty()) {
            throw new IllegalArgumentException("结论包含结果数据中不存在的实体: " + unknownEntities);
        }
        validateEntityFigureRelationships(conclusion, result);
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

    private void validateEntityFigureRelationships(String conclusion, TabularResult result) {
        Map<String, Set<String>> figuresByEntity = figuresByEntity(result);
        if (figuresByEntity.isEmpty()) return;
        for (String clause : conclusion.split("(?:[，,；;。！？?\\n]+|(?<!\\d)[.!](?!\\d))")) {
            LinkedHashSet<String> entities = new LinkedHashSet<>();
            java.util.regex.Matcher entityMatcher = DIGIT_IDENTIFIER.matcher(clause);
            while (entityMatcher.find()) {
                String normalized = entityMatcher.group().toLowerCase(Locale.ROOT);
                if (figuresByEntity.containsKey(normalized)) entities.add(normalized);
            }
            java.util.regex.Matcher figureMatcher = NUMBER.matcher(clause);
            List<String> figures = new ArrayList<>();
            while (figureMatcher.find()) figures.add(normalize(figureMatcher.group()));
            if (entities.isEmpty() || figures.isEmpty()) continue;
            if (entities.size() != 1) {
                throw new IllegalArgumentException("结论中的实体与数字对应关系不明确: " + clause.strip());
            }
            String entity = entities.iterator().next();
            if (!figuresByEntity.get(entity).containsAll(figures)) {
                throw new IllegalArgumentException("结论中的实体与数字对应关系不受结果行支持: " + clause.strip());
            }
        }
    }

    private Map<String, Set<String>> figuresByEntity(TabularResult result) {
        Map<String, Set<String>> relationships = new LinkedHashMap<>();
        for (List<Object> row : result.rows()) {
            Set<String> entities = new LinkedHashSet<>();
            Set<String> figures = new LinkedHashSet<>();
            for (Object value : row) {
                if (value == null) continue;
                java.util.regex.Matcher entityMatcher = DIGIT_IDENTIFIER.matcher(value.toString());
                while (entityMatcher.find()) {
                    entities.add(entityMatcher.group().toLowerCase(Locale.ROOT));
                }
                if (value instanceof Number number) {
                    figures.add(normalize(stripTrailingZeros(number)));
                } else if (NUMBER.matcher(value.toString()).matches()) {
                    figures.add(normalize(value.toString()));
                }
            }
            for (String entity : entities) {
                relationships.computeIfAbsent(entity, ignored -> new LinkedHashSet<>()).addAll(figures);
            }
        }
        return relationships;
    }

    private List<String> supportedValues(TabularResult result) {
        List<String> values = new ArrayList<>(result.columnNames());
        for (List<Object> row : result.rows()) {
            for (Object value : row) {
                if (value != null) {
                    values.add(value.toString());
                }
            }
        }
        return values;
    }

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
