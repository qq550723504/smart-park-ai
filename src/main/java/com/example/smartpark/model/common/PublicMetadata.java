package com.example.smartpark.model.common;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared validation for bounded text that is safe to expose as public metadata. */
public final class PublicMetadata {

    public static final int MAX_TITLE_LENGTH = 160;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private PublicMetadata() { }

    public static boolean isSafeTitle(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_TITLE_LENGTH
                && value.codePoints().noneMatch(PublicMetadata::isUnsafePublicCharacter);
    }

    public static String requireTitle(String value) {
        Objects.requireNonNull(value, "title");
        if (!isSafeTitle(value)) {
            throw new IllegalArgumentException("title must be bounded public metadata");
        }
        return value;
    }

    public static String requireIdentifier(String value, String fieldName) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a safe opaque identifier");
        }
        return value;
    }

    private static boolean isUnsafePublicCharacter(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE,
                    Character.SURROGATE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }
}
