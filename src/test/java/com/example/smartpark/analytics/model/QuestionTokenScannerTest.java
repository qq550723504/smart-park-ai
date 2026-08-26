package com.example.smartpark.analytics.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionTokenScannerTest {

    @Test
    void scansDateShapedEntityAsOneIdentifierToken() {
        assertThat(QuestionTokenScanner.entityIdentifiers("MTR-2026-08-01表计的能耗")
                .stream().map(QuestionTokenScanner.Token::text))
                .containsExactly("MTR-2026-08-01");
    }

    @Test
    void rejectsDateSpanInsideIdentifierButAcceptsStandaloneDate() {
        assertThat(QuestionTokenScanner.isStandaloneSpan("MTR-2026-08-01表计", 4, 14)).isFalse();
        assertThat(QuestionTokenScanner.isStandaloneSpan("2026-08-01能耗", 0, 10)).isTrue();
    }
}
