package com.example.smartpark.model.customer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceResultTest {

    @Test
    void rejectsUnsafeCitationIdsEvenWhenConstructedOutsideKnowledgeMatch() {
        assertThatThrownBy(() -> new CustomerServiceResult(
                "cs-1", "PARKING", "supported answer", List.of("Safe title"), false, null,
                "SUPPORTED", List.of("KD\nPRIVATE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compatibilityConstructorRequiresExplicitCitationIdsForSupportedResults() {
        assertThatThrownBy(() -> new CustomerServiceResult(
                "cs-1", "PARKING", "supported answer", List.of("Visitor parking guide"), false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit citationIds");
    }

    @Test
    void canonicalConstructorKeepsHumanReadableSourcesSeparateFromStableCitationIds() {
        CustomerServiceResult result = new CustomerServiceResult(
                "cs-1", "PARKING", "supported answer", List.of("Visitor parking guide"), false, null,
                "SUPPORTED", List.of("KD-PARKING-001"));

        assertThat(result.knowledgeSources()).containsExactly("Visitor parking guide");
        assertThat(result.citationIds()).containsExactly("KD-PARKING-001");
    }

    @Test
    void everyNonSupportedReasonRequiresHumanHandoff() {
        List.of("HUMAN_HANDOFF", "RETRIEVAL_UNAVAILABLE", "INSUFFICIENT_EVIDENCE", "POLICY_LIMIT")
                .forEach(reason -> assertThatThrownBy(() -> new CustomerServiceResult(
                        "cs-1", "PARKING", "handoff", List.of(), false, null,
                        reason, List.of()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("must require human handoff"));
    }

    @Test
    void rejectsUnknownReasons() {
        assertThatThrownBy(() -> new CustomerServiceResult(
                "cs-1", "PARKING", "handoff", List.of(), true,
                new CustomerTicket("ticket-1", "cs-1", "PARKING", "WAITING_AGENT", "parking", java.time.Instant.EPOCH),
                "UNKNOWN", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported reason");
    }
}
