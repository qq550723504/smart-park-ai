package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards the conclusion stage: every number the model mentions must be
 * traceable to executed result values or row/column counts. Unsupported
 * numbers are rejected — the SQL and result table survive without a conclusion.
 */
public class AnalysisSummaryValidator {

    // Digits inside identifiers (B1, MTR-2) are not figures; require non-alphanumeric context.
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9])-?[0-9]+(?:\\.[0-9]+)?(?![0-9A-Za-z])");

    public String validate(String conclusion, QueryPlan plan, TabularResult result) {
        if (conclusion == null || conclusion.isBlank()) {
            throw new IllegalArgumentException("结论不能为空");
        }
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
