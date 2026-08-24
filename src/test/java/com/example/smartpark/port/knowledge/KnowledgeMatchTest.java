package com.example.smartpark.port.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeMatchTest {

    @Test
    void rejectsCitationIdsThatAreNotSafeOpaqueIdentifiers() {
        assertThatThrownBy(() -> new KnowledgeMatch("KD\nPRIVATE", "Safe title", 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeMatch("a".repeat(129), "Safe title", 0.8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTitlesThatCouldBecomeUnboundedOrMultilinePublicMetadata() {
        assertThatThrownBy(() -> new KnowledgeMatch("KD-SAFE-001", "private body\nsecond line", 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeMatch("KD-SAFE-001", "a".repeat(161), 0.8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
