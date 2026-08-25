package com.example.smartpark.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Real-database contract for the analytics capability. Every value comes from
 * environment-overridable configuration; there is no in-memory fallback.
 */
@ConfigurationProperties(prefix = "smartpark.analytics")
public class AnalyticsProperties {

    private boolean enabled;
    private Duration statementTimeout = Duration.ofSeconds(3);
    private Duration analysisTimeout = Duration.ofSeconds(60);
    private Duration clarificationTimeout = Duration.ofMinutes(5);
    private int maxRows = 500;
    private long maxResultBytes = 1024L * 1024L;
    private double maxPlanCost = 1_000_000.0;
    private final Datasource datasource = new Datasource();

    /** Fails startup when enabled=true but the real database contract is incomplete. */
    public void validateUsable() {
        if (!enabled) {
            return;
        }
        if (isBlank(datasource.url) || isBlank(datasource.username) || isBlank(datasource.password)
                || isBlank(datasource.adminUsername) || isBlank(datasource.adminPassword)) {
            throw new IllegalStateException(
                    "smartpark.analytics.enabled=true 需要完整的数据源配置（url/username/password/admin-username/admin-password）");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getStatementTimeout() { return statementTimeout; }
    public void setStatementTimeout(Duration statementTimeout) { this.statementTimeout = statementTimeout; }
    public Duration getAnalysisTimeout() { return analysisTimeout; }
    public void setAnalysisTimeout(Duration analysisTimeout) { this.analysisTimeout = analysisTimeout; }
    public Duration getClarificationTimeout() { return clarificationTimeout; }
    public void setClarificationTimeout(Duration clarificationTimeout) {
        if (clarificationTimeout == null || clarificationTimeout.isZero() || clarificationTimeout.isNegative()) {
            throw new IllegalArgumentException("clarification-timeout must be positive");
        }
        this.clarificationTimeout = clarificationTimeout;
    }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) {
        // Must match SqlAstGuard's LIMIT contract (1..500): a cap the guard can
        // never satisfy would silently truncate every analysis to zero rows.
        int supportedMaximum = com.example.smartpark.analytics.sql.SqlAstGuard.MAX_ROWS;
        if (maxRows < 1 || maxRows > supportedMaximum) {
            throw new IllegalArgumentException("max-rows must be between 1 and " + supportedMaximum);
        }
        this.maxRows = maxRows;
    }
    public long getMaxResultBytes() { return maxResultBytes; }
    public void setMaxResultBytes(long maxResultBytes) {
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("max-result-bytes must be positive");
        }
        this.maxResultBytes = maxResultBytes;
    }
    public double getMaxPlanCost() { return maxPlanCost; }
    public void setMaxPlanCost(double maxPlanCost) {
        if (!Double.isFinite(maxPlanCost) || maxPlanCost <= 0) {
            throw new IllegalArgumentException("max-plan-cost must be finite and positive");
        }
        this.maxPlanCost = maxPlanCost;
    }
    public Datasource getDatasource() { return datasource; }

    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private String adminUsername;
        private String adminPassword;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    }
}
