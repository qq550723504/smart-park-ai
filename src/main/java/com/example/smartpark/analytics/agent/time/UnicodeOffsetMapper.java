package com.example.smartpark.analytics.agent.time;

/** Converts the sidecar's Unicode code-point offsets to Java UTF-16 offsets. */
public final class UnicodeOffsetMapper {

    private UnicodeOffsetMapper() {
    }

    public static Span toUtf16(String question, int startCodePoint, int endCodePoint) {
        if (question == null || startCodePoint < 0 || endCodePoint < startCodePoint
                || endCodePoint > question.codePointCount(0, question.length())) {
            throw new IllegalArgumentException("code-point span is outside question");
        }
        int start = question.offsetByCodePoints(0, startCodePoint);
        int end = question.offsetByCodePoints(0, endCodePoint);
        return new Span(start, end);
    }

    public static Span toCodePoints(String question, int startUtf16, int endUtf16) {
        if (question == null || startUtf16 < 0 || endUtf16 < startUtf16
                || endUtf16 > question.length() || splitsSurrogate(question, startUtf16)
                || splitsSurrogate(question, endUtf16)) {
            throw new IllegalArgumentException("UTF-16 span is outside a code-point boundary");
        }
        return new Span(question.codePointCount(0, startUtf16), question.codePointCount(0, endUtf16));
    }

    private static boolean splitsSurrogate(String value, int offset) {
        return offset > 0 && offset < value.length()
                && Character.isLowSurrogate(value.charAt(offset))
                && Character.isHighSurrogate(value.charAt(offset - 1));
    }

    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("span must be ordered");
            }
        }
    }
}
