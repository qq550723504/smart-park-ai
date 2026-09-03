package com.example.smartpark.securityincident;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIncidentStoreTest {

    @Test
    void evictsTheOldestIncidentWhenCapacityIsReached() {
        SecurityIncidentStore store = new SecurityIncidentStore(1);
        SecurityIncident first = SecurityIncidentFixtures.incident("INC-1", Instant.parse("2026-09-02T08:00:00Z"));
        SecurityIncident second = SecurityIncidentFixtures.incident("INC-2", Instant.parse("2026-09-02T08:01:00Z"));

        store.save(first);
        store.save(second);

        assertThat(store.get("INC-1")).isEmpty();
        assertThat(store.get("INC-2")).contains(second);
    }
}
