package com.example.smartpark.model.common;

import java.util.Objects;

/** Shared validation for bounded text that is safe to expose as public metadata. */
public final class PublicMetadata {

    public static final int MAX_TITLE_LENGTH = 160;

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

    private static boolean isUnsafePublicCharacter(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE,
                    Character.SURROGATE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }
}
