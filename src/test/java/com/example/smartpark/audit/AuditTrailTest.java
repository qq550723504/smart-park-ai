package com.example.smartpark.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTrailTest {

    @Test
    void auditEntryContainsActionActorResourceAndOutcomeOnly() {
        AuditEntry entry = new AuditEntry(
                "CUSTOMER_AGENT", "UPDATE_TICKET", "CS-0001", "SUCCESS",
                Instant.parse("2026-08-23T02:00:00Z"));

        assertThat(entry.actorRole()).isEqualTo("CUSTOMER_AGENT");
        assertThat(entry.action()).isEqualTo("UPDATE_TICKET");
        assertThat(entry.resourceId()).isEqualTo("CS-0001");
        assertThat(entry.toString()).doesNotContain("手机号", "身份证");
    }
}
