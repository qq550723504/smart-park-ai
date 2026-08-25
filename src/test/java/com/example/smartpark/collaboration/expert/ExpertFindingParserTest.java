package com.example.smartpark.collaboration.expert;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.FindingStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertFindingParserTest {
    private final ExpertFindingParser parser = new ExpertFindingParser();

    @Test void parsesTheCommonFindingShape() {
        var finding = parser.parse("""
                {"domain":"ENERGY","status":"SUPPORTED","conclusion":"above baseline","evidenceRefs":["tool:energy:1"],"confidence":0.8,"nextChecks":[]}
                """, ExpertDomain.ENERGY);
        assertThat(finding.status()).isEqualTo(FindingStatus.SUPPORTED);
        assertThat(finding.evidenceRefs()).containsExactly("tool:energy:1");
    }

    @Test void rejectsCrossDomainOrMalformedFinding() {
        assertThatThrownBy(() -> parser.parse("""
                {"domain":"SECURITY","status":"SUPPORTED","conclusion":"x","evidenceRefs":[],"confidence":0,"nextChecks":[]}
                """, ExpertDomain.ENERGY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{}", ExpertDomain.ENERGY)).isInstanceOf(IllegalArgumentException.class);
    }
}
