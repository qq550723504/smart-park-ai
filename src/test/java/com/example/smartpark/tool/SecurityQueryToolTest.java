package com.example.smartpark.tool;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.security.SecurityQueryTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityQueryToolTest {

    @Test
    void returnsOnlyTheRedactedSummaryForAKnownSecurityEvent() {
        SecurityQueryTool tool = new SecurityQueryTool(new MockParkFixture().security());

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.error()).isNull();
        assertThat(result.event().eventType()).isEqualTo("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(result.event().evidenceSummary()).startsWith("REDACTED:");
        assertThat(result.event().evidenceSummary()).doesNotContain("base64", "data:image", "身份证");
        assertThat(result.notice()).contains("No raw media");
    }

    @Test
    void unknownEventReturnsSafeErrorWithoutInventingEvidence() {
        SecurityQueryTool.SecurityLookupResult result = new SecurityQueryTool(new MockParkFixture().security())
                .lookupSecurityEvent("missing-event");

        assertThat(result.event()).isNull();
        assertThat(result.error()).contains("Unknown security event");
    }
}
