package com.example.smartpark.collaboration.expert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceLedgerTest {
    @Test void recordsUniqueEvidenceReferences() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:energy:1");
        ledger.record("tool:energy:1");
        assertThat(ledger.snapshot()).containsExactly("tool:energy:1");
    }

    @Test void rejectsBlankReferences() {
        assertThatThrownBy(() -> new EvidenceLedger().record(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
