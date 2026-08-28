package com.example.smartpark.analytics.agent.time;

import com.example.smartpark.analytics.agent.TimeIntentResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JioNlpTimeIntentProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void mapsCodePointMentionAndRangeToJavaTimeEvidence() {
        JioNlpTimeIntentProvider provider = new JioNlpTimeIntentProvider(request ->
                new TimeParserResponse("jionlp", "1.5.29", request.referenceInstant(), request.timezone(),
                        "PARSED", List.of(new TimeParserMention("今天", 1, 3, "time_point", "accurate",
                                "2026-08-24T16:00:00Z", "2026-08-25T00:00:00Z", false)), null));

        TimeIntentResult result = provider.resolve("🔔今天能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.PARSED);
        assertThat(result.mentions()).singleElement().satisfies(mention -> {
            assertThat(mention.start()).isEqualTo(2);
            assertThat(mention.end()).isEqualTo(4);
            assertThat(mention.text()).isEqualTo("今天");
        });
        assertThat(result.timeRange().from()).isEqualTo(Instant.parse("2026-08-24T16:00:00Z"));
    }

    @Test
    void sendsEntityIdentifiersAsExcludedCodePointSpans() {
        var captured = new java.util.concurrent.atomic.AtomicReference<TimeParserRequest>();
        JioNlpTimeIntentProvider provider = new JioNlpTimeIntentProvider(request -> {
            captured.set(request);
            return new TimeParserResponse("jionlp", "1.5.29", request.referenceInstant(), request.timezone(),
                    "NONE", List.of(), null);
        });

        TimeIntentResult result = provider.resolve("MTR-2026-08-01表计能耗", NOW);

        assertThat(result.status()).isEqualTo(TimeIntentResult.Status.NONE);
        assertThat(captured.get().excludedSpans())
                .containsExactly(new UnicodeOffsetMapper.Span(0, 14));
    }

    @Test
    void rejectsAParserMentionThatStillOverlapsAnEntityIdentifier() {
        JioNlpTimeIntentProvider provider = new JioNlpTimeIntentProvider(request ->
                new TimeParserResponse("jionlp", "1.5.29", request.referenceInstant(), request.timezone(),
                        "PARSED", List.of(new TimeParserMention("MTR-2026-08-01", 0, 14, "time_point", "accurate",
                                "2026-07-31T16:00:00Z", "2026-08-01T16:00:00Z", false)), null));

        assertThatThrownBy(() -> provider.resolve("MTR-2026-08-01表计能耗", NOW))
                .isInstanceOf(TimeParserInvalidResponseException.class);
    }
}
