package com.example.smartpark.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsRoleCredentialsTest {

    @Test
    void doublesSingleQuotesSoGeneratedSecretsStayLiteral() {
        assertThat(AnalyticsRoleCredentials.quoteLiteral("abc'def")).isEqualTo("'abc''def'");
        assertThat(AnalyticsRoleCredentials.quoteLiteral("it's \"fine\"")).isEqualTo("'it''s \"fine\"'");
        assertThat(AnalyticsRoleCredentials.quoteLiteral("")).isEqualTo("''");
        assertThat(AnalyticsRoleCredentials.quoteLiteral("plain-pass-123")).isEqualTo("'plain-pass-123'");
    }
}
