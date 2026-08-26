package com.example.smartpark.analytics.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared token boundaries for identifiers and other structured question values. */
public final class QuestionTokenScanner {

    private static final Pattern ENTITY_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(?:[A-Za-z]\\d+|[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+)"
                    + "(?![A-Za-z0-9_-])");

    private QuestionTokenScanner() {
    }

    public static List<Token> entityIdentifiers(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = ENTITY_IDENTIFIER.matcher(question);
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return List.copyOf(tokens);
    }

    public static boolean isStandaloneSpan(String question, int start, int end) {
        Objects.requireNonNull(question, "question");
        if (start < 0 || end < start || end > question.length()) {
            throw new IllegalArgumentException("span is outside question");
        }
        return (start == 0 || !isIdentifierCharacter(question.charAt(start - 1)))
                && (end == question.length() || !isIdentifierCharacter(question.charAt(end)));
    }

    private static boolean isIdentifierCharacter(char character) {
        return character < 128 && (Character.isLetterOrDigit(character)
                || character == '_' || character == '-');
    }

    public record Token(String text, int start, int end) {
        public Token {
            Objects.requireNonNull(text, "text");
            if (start < 0 || end <= start || end - start != text.length()) {
                throw new IllegalArgumentException("invalid token span");
            }
        }
    }
}
