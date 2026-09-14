package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityDispositionRecordTest {

    private static final Instant DECIDED_AT = Instant.parse("2026-09-14T02:00:00Z");

    @Test
    void unreviewedHasNoDecisionProvenance() {
        SecurityDispositionRecord record = SecurityDispositionRecord.unreviewed();

        assertThat(record.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);
        assertThat(record.source()).isEqualTo(SecurityDispositionSource.NONE);
        assertThat(record.actor()).isNull();
        assertThat(record.modelId()).isNull();
        assertThat(record.modelVersion()).isNull();
        assertThat(record.evidenceRef()).isNull();
        assertThat(record.decidedAt()).isNull();
    }

    @Test
    void acceptsHumanReviewedFalsePositive() {
        SecurityDispositionRecord record = new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.HUMAN_REVIEW,
                "APPROVER-1", null, null, "human-review:INC-1", DECIDED_AT);

        assertThat(record.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(record.source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
        assertThat(record.actor()).isEqualTo("APPROVER-1");
    }

    @Test
    void acceptsHumanReviewedConfirmedIncidentInconclusiveAndDuplicate() {
        for (SecurityDisposition disposition : new SecurityDisposition[]{
                SecurityDisposition.CONFIRMED_INCIDENT, SecurityDisposition.INCONCLUSIVE, SecurityDisposition.DUPLICATE}) {
            SecurityDispositionRecord record = new SecurityDispositionRecord(
                    disposition, SecurityDispositionSource.HUMAN_REVIEW, "APPROVER-1", null, null,
                    "human-review:INC-1", DECIDED_AT);
            assertThat(record.disposition()).isEqualTo(disposition);
        }
    }

    @Test
    void rejectsFalsePositiveWithoutLegalProvenance() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.NONE,
                null, null, null, null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FALSE_POSITIVE");
    }

    @Test
    void rejectsHumanReviewWithoutActor() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.HUMAN_REVIEW,
                "  ", null, null, "human-review:INC-1", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void requiresRegisteredModelIdVersionAndEvidence() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.REGISTERED_MODEL,
                null, "model-fp", null, "evidence:1", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelVersion");
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.REGISTERED_MODEL,
                null, null, "1.0", "evidence:1", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.REGISTERED_MODEL,
                null, "model-fp", "1.0", " ", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRef");
    }

    @Test
    void acceptsRegisteredModelFalsePositiveWithFullProvenance() {
        SecurityDispositionRecord record = new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.REGISTERED_MODEL,
                null, "model-fp", "1.0", "evidence:run-42", DECIDED_AT);

        assertThat(record.source()).isEqualTo(SecurityDispositionSource.REGISTERED_MODEL);
        assertThat(record.modelId()).isEqualTo("model-fp");
        assertThat(record.modelVersion()).isEqualTo("1.0");
    }

    @Test
    void rejectsUnreviewedWithDecisionSource() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.UNREVIEWED, SecurityDispositionSource.HUMAN_REVIEW,
                "APPROVER-1", null, null, null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNREVIEWED");
    }

    @Test
    void rejectsDecidedDispositionWithoutDecidedAt() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.CONFIRMED_INCIDENT, SecurityDispositionSource.HUMAN_REVIEW,
                "APPROVER-1", null, null, "human-review:INC-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedAt");
    }

    @Test
    void rejectsMissingDispositionOrSource() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                null, SecurityDispositionSource.NONE, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.CONFIRMED_INCIDENT, null, "APPROVER-1", null, null, "e", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void falsePositiveWithHumanReviewRequiresEvidenceReference() {
        assertThatThrownBy(() -> new SecurityDispositionRecord(
                SecurityDisposition.FALSE_POSITIVE, SecurityDispositionSource.HUMAN_REVIEW,
                "APPROVER-1", null, null, null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRef");
    }
}
