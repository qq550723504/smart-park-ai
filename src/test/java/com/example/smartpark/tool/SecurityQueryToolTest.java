package com.example.smartpark.tool;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.tool.security.SecurityQueryTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityQueryToolTest {

    @Test
    void returnsOnlyTheRedactedSummaryForAKnownSecurityEvent() {
        SecurityQueryTool tool = new SecurityQueryTool(new MockParkFixture().security(), List.of());

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.error()).isNull();
        assertThat(result.event().eventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
        assertThat(result.event().rawEventType()).isEqualTo("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(result.event().evidenceSummary()).startsWith("REDACTED:");
        assertThat(result.event().evidenceSummary()).doesNotContain("base64", "data:image", "身份证");
        assertThat(result.notice()).contains("No raw media");
    }

    @Test
    void unknownEventReturnsSafeErrorWithoutInventingEvidence() {
        SecurityQueryTool.SecurityLookupResult result = new SecurityQueryTool(new MockParkFixture().security(), List.of())
                .lookupSecurityEvent("missing-event");

        assertThat(result.event()).isNull();
        assertThat(result.error()).contains("Unknown security event");
    }

    @Test
    void resolvesAdapterEventsWhenALegacyReaderIsAlsoRegistered() {
        SecurityEventReader emptyLegacyReader = new SecurityEventReader() {
            @Override
            public SecurityEvent getEvent(String eventId) {
                throw new NoSuchElementException("security event not found: " + eventId);
            }

            @Override
            public List<SecurityEvent> listEvents() {
                return List.of();
            }
        };
        SecurityQueryTool tool = new SecurityQueryTool(emptyLegacyReader, List.of(new MockParkFixture().security()));

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.error()).isNull();
        assertThat(result.event().eventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
    }
}
