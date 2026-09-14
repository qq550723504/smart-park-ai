package com.example.smartpark.securityincident;

import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.model.security.SecurityEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityIncidentTest {

    private static final Instant AT = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void allowsALegacyReviewedIncidentWithoutARecordedDisposition() {
        SecurityIncident incident = new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.REVIEWED, AT, AT, List.of("SEC-1"), List.of(),
                List.of(new SecurityIncidentEvidence("SEC-1", AT, "REDACTED: safe")), List.of(), List.of(), AT, null);

        assertThat(incident.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);
        assertThat(incident.dispositionRecord()).isEqualTo(SecurityDispositionRecord.unreviewed());
    }

    @Test
    void rejectsADecidedDispositionWithoutReviewedAt() {
        SecurityDispositionRecord record = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.HUMAN_REVIEW, "APPROVER", null, null, "human-review:INC-1", AT);

        assertThatThrownBy(() -> new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.REVIEWED, AT, AT, List.of("SEC-1"), List.of(),
                List.of(new SecurityIncidentEvidence("SEC-1", AT, "REDACTED: safe")), List.of(), List.of(), null,
                null, SecurityDisposition.FALSE_POSITIVE, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decided disposition requires reviewedAt");
    }

    @Test
    void rejectsADispositionRecordThatDoesNotMatchTheIncidentDisposition() {
        assertThatThrownBy(() -> new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.OPEN, AT, AT, List.of("SEC-1"), List.of(),
                List.of(new SecurityIncidentEvidence("SEC-1", AT, "REDACTED: safe")), List.of(), List.of(), null,
                null, SecurityDisposition.CONFIRMED_INCIDENT, SecurityDispositionRecord.unreviewed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disposition record must match");
    }

    @Test
    void appliesADispositionWhenReviewing() {
        SecurityIncident open = new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.OPEN, AT, AT, List.of("SEC-1"), List.of(),
                List.of(new SecurityIncidentEvidence("SEC-1", AT, "REDACTED: safe")), List.of(), List.of(), null, null);
        SecurityDispositionRecord record = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.HUMAN_REVIEW, "APPROVER", null, null, "human-review:INC-1", AT);

        SecurityIncident reviewed = open.review(record, AT);

        assertThat(reviewed.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(reviewed.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(reviewed.dispositionRecord()).isEqualTo(record);
        assertThat(reviewed.reviewedAt()).isEqualTo(AT);
    }

    @Test
    void mapsLegacyEventTypeLabelsToStandardTypes() {
        SecurityIncident incident = new SecurityIncident("INC-1", "PARK-A", "A1",
                "UNAUTHORIZED_ACCESS_ATTEMPT", SecurityIncidentRisk.HIGH, SecurityIncidentStatus.OPEN, AT, AT,
                List.of("SEC-1"), List.of(), List.of(), List.of(), List.of(), null, null);

        assertThat(incident.standardEventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
    }
}
