package com.example.smartpark.analytics;

/** Startup boundary for binding the configured runtime password to the migrated read-only role. */
@FunctionalInterface
interface AnalyticsRoleCredentialProvisioner {
    void provision(AnalyticsProperties properties);
}
