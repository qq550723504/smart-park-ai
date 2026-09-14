package com.example.smartpark.model.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared identifier safety policy: security identifiers must never carry camera
 * credentials, internal URLs or tokens into the domain model or the UI.
 *
 * <p>The check targets credential-bearing syntax (a URL scheme, a
 * {@code token=}/{@code password:} assignment, an {@code apikey-...} value or a
 * bare secret) rather than the credential words themselves, so ordinary domain
 * terminology such as {@code ACCESS_TOKEN_REJECTED} or
 * {@code INVALID_CREDENTIAL} stays usable.
 */
final class SecurityIdentifierPolicy {
    private static final List<Pattern> FORBIDDEN_SYNTAX = List.of(
            // A URL, including the rtsp://user:pass@host form that leaks credentials.
            Pattern.compile("://"),
            // A credential keyword assigned a value, e.g. token=abc or password:secret.
            Pattern.compile("(?i)(?:token|password|passwd|secret|apikey|api[_-]?key|credential)\\s*[=:]"),
            // An API-key value such as apikey-123 or api_key-123.
            Pattern.compile("(?i)(?:apikey|api[_-]?key)[-_]\\S"));
    private static final Set<String> BARE_SECRETS = Set.of(
            "token", "password", "passwd", "secret", "apikey", "api_key", "api-key", "credential");

    private SecurityIdentifierPolicy() {
    }

    static String requireSafe(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return optionalSafe(value, field);
    }

    static String optionalSafe(String value, String field) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        String searchable = normalized.toLowerCase(Locale.ROOT);
        boolean unsafe = BARE_SECRETS.contains(searchable)
                || FORBIDDEN_SYNTAX.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
        if (unsafe) {
            throw new IllegalArgumentException(field + " must not contain credentials or URLs");
        }
        return normalized;
    }
}
