package com.example.smartpark.analytics.catalog;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical categorical filter values shared by question inference and the
 * immutable query-plan boundary.
 */
public final class CategoricalFilterVocabulary {

    private static final Map<String, Map<String, String>> TERMS = Map.of(
            "status", Map.ofEntries(
                    Map.entry("open", "OPEN"), Map.entry("未处理", "OPEN"),
                    Map.entry("resolved", "RESOLVED"), Map.entry("已解决", "RESOLVED"),
                    Map.entry("已处理", "RESOLVED")),
            "risk_level", Map.ofEntries(
                    Map.entry("high", "HIGH"), Map.entry("高风险", "HIGH"),
                    Map.entry("medium", "MEDIUM"), Map.entry("中风险", "MEDIUM"),
                    Map.entry("low", "LOW"), Map.entry("低风险", "LOW")),
            "category", Map.ofEntries(
                    Map.entry("temperature", "TEMPERATURE"), Map.entry("温度", "TEMPERATURE"),
                    Map.entry("power", "POWER"), Map.entry("用电", "POWER"), Map.entry("电力", "POWER"),
                    Map.entry("配电", "POWER"), Map.entry("humidity", "HUMIDITY"), Map.entry("湿度", "HUMIDITY"),
                    Map.entry("access", "ACCESS"), Map.entry("门禁", "ACCESS"), Map.entry("安防", "ACCESS")));

    private CategoricalFilterVocabulary() {}

    public static String canonicalValue(String dimension, String value) {
        if (value == null) return null;
        Map<String, String> vocabulary = TERMS.get(dimension);
        return vocabulary == null
                ? value
                : vocabulary.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    public static Set<String> matchingCanonicalValues(String dimension, String question) {
        Map<String, String> vocabulary = TERMS.get(dimension);
        if (vocabulary == null || question == null) return Set.of();
        Set<String> matches = new LinkedHashSet<>();
        vocabulary.forEach((term, canonical) -> {
            if (matchesTerm(question, term) && !isNegatedTerm(question, term)) {
                matches.add(canonical);
            }
        });
        return Set.copyOf(matches);
    }

    public static boolean valueAppearsInQuestion(String dimension, String value, String question) {
        String canonical = canonicalValue(dimension, value);
        return matchingCanonicalValues(dimension, question).contains(canonical);
    }

    public static boolean containsNegatedTerm(String dimension, String question) {
        Map<String, String> vocabulary = TERMS.get(dimension);
        if (vocabulary == null || question == null) return false;
        for (String term : vocabulary.keySet()) {
            if (isNegatedTerm(question, term)) return true;
        }
        // "unresolved" is not a substring match for "resolved". It is an
        // unsupported negative predicate and must not silently become a total query.
        return dimension.equals("status") && Pattern.compile("(?i)(?<![A-Za-z0-9_])unresolved(?![A-Za-z0-9_])")
                .matcher(question).find();
    }

    private static boolean matchesTerm(String question, String term) {
        String normalized = question.toLowerCase(Locale.ROOT);
        if (isAsciiToken(term)) {
            if (Set.of("high", "medium", "low").contains(term)
                    && !matchesRiskContext(normalized, term)) {
                return false;
            }
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(term)
                            + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                    .matcher(normalized).find();
        }
        return normalized.contains(term);
    }

    private static boolean matchesRiskContext(String question, String risk) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:risk(?:[ _-]?level)?\\s*[:=]?\\s*"
                        + Pattern.quote(risk) + "|" + Pattern.quote(risk)
                        + "\\s+(?:risk(?:[ _-]?level)?))(?![A-Za-z0-9_])")
                .matcher(question).find();
    }

    private static boolean isNegatedTerm(String question, String term) {
        if (!isAsciiToken(term)) return false;
        String normalized = question.toLowerCase(Locale.ROOT);
        var matcher = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(term)
                        + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(normalized);
        while (matcher.find()) {
            String before = normalized.substring(0, matcher.start()).stripTrailing();
            if (before.endsWith("not") || before.endsWith("no")) return true;
        }
        return false;
    }

    private static boolean isAsciiToken(String term) {
        return term.matches("[a-z0-9_]+");
    }
}
