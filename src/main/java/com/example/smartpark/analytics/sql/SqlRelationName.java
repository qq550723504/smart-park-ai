package com.example.smartpark.analytics.sql;

import net.sf.jsqlparser.schema.Table;

import java.util.Locale;
import java.util.Objects;

/** PostgreSQL relation identity with schema and relation boundaries preserved. */
record SqlRelationName(String schema, String relation) {

    SqlRelationName {
        schema = Objects.requireNonNull(schema, "schema");
        relation = Objects.requireNonNull(relation, "relation");
    }

    static SqlRelationName from(Table table) {
        Objects.requireNonNull(table, "table");
        var ast = table.getASTNode();
        if (ast != null && ast.jjtGetFirstToken() != null) {
            String firstToken = ast.jjtGetFirstToken().image;
            if (isSingleQuotedDottedIdentifier(firstToken)) {
                return new SqlRelationName("", component(firstToken));
            }
        }
        return new SqlRelationName(component(table.getSchemaName()), component(table.getName()));
    }

    static SqlRelationName parseCatalogName(String qualifiedName) {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        int separator = qualifiedName.indexOf('.');
        if (separator < 1 || separator != qualifiedName.lastIndexOf('.')) {
            throw new IllegalArgumentException("catalog relation must contain one schema boundary");
        }
        return new SqlRelationName(
                component(qualifiedName.substring(0, separator)),
                component(qualifiedName.substring(separator + 1)));
    }

    static String component(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }
        if (identifier.startsWith("`") || identifier.endsWith("`")) {
            throw new IllegalArgumentException("PostgreSQL identifiers cannot use backticks");
        }
        if (identifier.startsWith("\"") || identifier.endsWith("\"")) {
            if (identifier.length() < 2 || !identifier.startsWith("\"") || !identifier.endsWith("\"")) {
                throw new IllegalArgumentException("malformed quoted identifier");
            }
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier.toLowerCase(Locale.ROOT);
    }

    boolean isQualified() {
        return !schema.isEmpty() && !relation.isEmpty();
    }

    private static boolean isSingleQuotedDottedIdentifier(String token) {
        return token != null && token.length() >= 3
                && token.startsWith("\"") && token.endsWith("\"")
                && token.substring(1, token.length() - 1).contains(".");
    }
}
