package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecuritySourceRefTest {

    @Test
    void acceptsTheCanonicalSourceLessReference() {
        SecuritySourceRef unknown = SecuritySourceRef.unknown();

        assertThat(unknown.sourceType()).isEqualTo(SecuritySourceType.UNKNOWN);
        assertThat(unknown.sourceId()).isEqualTo(SecuritySourceRef.UNKNOWN_SOURCE_ID);
    }

    @Test
    void rejectsAnUnknownSourceThatCarriesANoncanonicalIdentifier() {
        // reference() never emits an UNKNOWN source, and two distinct UNKNOWN ids cannot be
        // told apart once the id is dropped, so such a source would be silently folded as a
        // legacy alias of every other UNKNOWN id with the same event id.
        assertThatThrownBy(() -> new SecuritySourceRef(SecuritySourceType.UNKNOWN, "vendor-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("vendor-a");
    }

    @Test
    void stillRejectsUnsafeIdentifiersBeforeTheUnknownCheck() {
        assertThatThrownBy(() -> new SecuritySourceRef(SecuritySourceType.UNKNOWN, "token:abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials or URLs");
    }
}
