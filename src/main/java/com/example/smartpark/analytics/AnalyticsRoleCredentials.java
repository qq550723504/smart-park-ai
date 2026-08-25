package com.example.smartpark.analytics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Binds the configured read-only credential to the analytics role at startup.
 * The role itself is created password-less by V1: interpolating a secret into
 * migration SQL cannot be escaped safely (Flyway substitutes placeholders
 * before PostgreSQL parses the script), so the application applies the exact
 * runtime credential itself. PostgreSQL utility statements do not accept bind
 * parameters, so the credential is embedded through {@link #quoteLiteral},
 * the single audited escaping point, instead of Flyway substitution.
 */
public final class AnalyticsRoleCredentials {

    public static final String ANALYTICS_ROLE = "smartpark_analytics_ro";

    private AnalyticsRoleCredentials() {
    }

    /** Doubles single quotes per the SQL standard (standard_conforming_strings keeps backslashes literal). */
    static String quoteLiteral(String raw) {
        return "'" + raw.replace("'", "''") + "'";
    }

    /**
     * Sets the analytics role's password to exactly the credential the runtime
     * will use, using the object-owner connection. Fails fast when the
     * migrations have not created the role yet, so an under-provisioned
     * database cannot silently pass startup.
     */
    public static void sync(String url, String adminUsername, String adminPassword,
                            String roPassword) {
        try (Connection admin = DriverManager.getConnection(url, adminUsername, adminPassword);
             var statement = admin.createStatement()) {
            statement.execute("ALTER ROLE " + ANALYTICS_ROLE + " WITH LOGIN PASSWORD "
                    + quoteLiteral(roPassword));
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "无法同步分析只读账号密码：请确认已应用 db/migration 迁移（角色 " + ANALYTICS_ROLE + " 必须已存在）",
                    failure);
        }
    }
}
