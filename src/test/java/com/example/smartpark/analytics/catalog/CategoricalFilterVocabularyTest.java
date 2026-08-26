package com.example.smartpark.analytics.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoricalFilterVocabularyTest {

    @Test
    void detectsNegatedChineseRiskTerms() {
        assertThat(CategoricalFilterVocabulary.containsNegatedTerm("risk_level", "非高风险告警数量"))
                .isTrue();
        assertThat(CategoricalFilterVocabulary.containsNegatedTerm("risk_level", "不是高风险状态的告警数量"))
                .isTrue();
    }

    @Test
    void detectsNegatedChineseStatusTerms() {
        assertThat(CategoricalFilterVocabulary.containsNegatedTerm("status", "不是未处理状态的告警数量"))
                .isTrue();
    }

    @Test
    void requiresStatusContextForBareOpen() {
        assertThat(CategoricalFilterVocabulary.matchingCanonicalValues("status", "open door 告警数量"))
                .isEmpty();
        assertThat(CategoricalFilterVocabulary.matchingCanonicalValues("status", "open status 告警数量"))
                .containsExactly("OPEN");
    }
}
