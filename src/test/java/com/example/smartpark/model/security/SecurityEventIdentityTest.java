package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventIdentityTest {
    private static final SecuritySourceRef ACCESS = new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "access-1");
    private static final SecuritySourceRef CAMERA = new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1");

    private static SecurityEventIdentity identity(SecuritySourceRef source, String eventId) {
        return new SecurityEventIdentity(source, eventId, "PARK-A", "A1");
    }

    @Test
    void distinguishesConcreteSourcesThatReuseTheSameLocalEventId() {
        assertThat(identity(ACCESS, "E").matches(identity(CAMERA, "E"))).isFalse();
        assertThat(identity(ACCESS, "E").matches(identity(ACCESS, "E"))).isTrue();
    }

    @Test
    void sameTypeWithDifferentSourceIdDoesNotMatch() {
        SecuritySourceRef otherAccess = new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "access-2");

        assertThat(identity(ACCESS, "E").matches(identity(otherAccess, "E"))).isFalse();
    }

    @Test
    void aliasesASourceLessRepresentationInBothDirections() {
        SecurityEventIdentity legacy = identity(SecuritySourceRef.unknown(), "E");

        assertThat(legacy.matches(identity(ACCESS, "E"))).isTrue();
        assertThat(identity(ACCESS, "E").matches(legacy)).isTrue();
        assertThat(legacy.matches(identity(CAMERA, "E"))).isTrue();
    }

    @Test
    void onlyMatchesTheSameEventAtTheSameLocation() {
        assertThat(identity(ACCESS, "E").matches(new SecurityEventIdentity(ACCESS, "E", "PARK-A", "A2"))).isFalse();
        assertThat(identity(ACCESS, "E").matches(new SecurityEventIdentity(ACCESS, "F", "PARK-A", "A1"))).isFalse();
    }

    @Test
    void qualifiesConcreteSourcesButKeepsLegacyMaterialStable() {
        SecurityEventIdentity legacy = identity(SecuritySourceRef.unknown(), "E");

        assertThat(legacy.material()).isEqualTo("1#E");
        assertThat(identity(ACCESS, "E").material()).isNotEqualTo(legacy.material());
        assertThat(identity(ACCESS, "E").material()).isEqualTo(identity(ACCESS, "E").material());
    }

    @Test
    void encodesSourceQualifiedReferencesThatSurviveDelimiters() {
        SecurityEventIdentity identity = new SecurityEventIdentity(
                new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "feed:one"), "E:1", "PARK-A", "A1");

        String reference = identity.reference();

        assertThat(reference).startsWith(SecurityEventIdentity.REFERENCE_PREFIX);
        assertThat(SecurityEventIdentity.isReference(reference)).isTrue();
        assertThat(SecurityEventIdentity.eventIdOfReference(reference)).isEqualTo("E:1");
    }

    @Test
    void keepsLegacyReferencesAsAnExplicitAlias() {
        String legacy = SecurityEventIdentity.legacyReference("E:1");

        assertThat(legacy).isEqualTo("security-event:E:1");
        assertThat(SecurityEventIdentity.eventIdOfReference(legacy)).isEqualTo("E:1");
        assertThat(SecurityEventIdentity.eventIdOfReference("device-health:PARK-1:HEALTHY")).isNull();
        // A token that merely begins with `source:` keeps resolving as a legacy event id.
        assertThat(SecurityEventIdentity.eventIdOfReference("security-event:source:not-encoded"))
                .isEqualTo("source:not-encoded");
    }

    @Test
    void rejectsAQualifiedReferenceWithAnUnknownSourceType() {
        // source() is delimiter-encoded as `length#value`; reference() never emits an
        // UNKNOWN source, so a token that names one (or a misspelled type) is malformed.
        String unknownType = "security-event:source:7#UNKNOWN:3#src:1#E";
        String misspelledType = "security-event:source:12#ACCESS_CNTRL:3#src:1#E";

        assertThatThrownBy(() -> SecurityEventIdentity.fromReference(unknownType, "PARK-A", "A1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source type");
        assertThatThrownBy(() -> SecurityEventIdentity.fromReference(misspelledType, "PARK-A", "A1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source type");
    }

    @Test
    void treatsAnOverflowingEncodedLengthAsAMalformedLegacyReference() {
        String malformed = "security-event:source:2147483647#x";

        assertThat(SecurityEventIdentity.eventIdOfReference(malformed)).isEqualTo("source:2147483647#x");
        SecurityEventIdentity identity = SecurityEventIdentity.fromReference(malformed, "PARK-A", "A1");
        assertThat(identity.isSourceLess()).isTrue();
        assertThat(identity.eventId()).isEqualTo("source:2147483647#x");
    }

    @Test
    void rejectsEventIdsThatWouldBeReparsedAsAQualifiedReference() {
        // reference() emits the source-less form as `security-event:` plus the bare id, so a
        // bare id that decodes as a qualified payload would resolve a different concrete source.
        String ambiguous = "source:14#ACCESS_CONTROL:4#feed:3#evt";

        assertThatThrownBy(() -> identity(SecuritySourceRef.unknown(), ambiguous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> identity(ACCESS, ambiguous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void canonicalizesQualifiedReferencesAndRejectsNonQualifiedTokens() {
        String reference = identity(ACCESS, "E").reference();

        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference)).isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference + ",")).isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference + ".")).isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference + ":")).isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(SecurityEventIdentity.legacyReference("E")))
                .isNull();
        assertThat(SecurityEventIdentity.canonicalQualifiedReference("SEC-1")).isNull();
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(
                "security-event:source:7#UNKNOWN:3#src:1#E")).isNull();
    }

    @Test
    void canonicalizesQualifiedReferencesWithDelimiterCharactersAndTrailingProse() {
        SecurityEventIdentity identity = new SecurityEventIdentity(
                new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "access/feed"), "E:1", "PARK-A", "A1");
        String reference = identity.reference();

        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference)).isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference + ", and SEC-ACCESS-001"))
                .isEqualTo(reference);
        assertThat(SecurityEventIdentity.canonicalQualifiedReference(reference + ".")).isEqualTo(reference);
    }

    @Test
    void qualifiesTheReferenceWithTheEventLocation() {
        SecurityEventIdentity here = identity(ACCESS, "E");
        SecurityEventIdentity elsewhere = new SecurityEventIdentity(ACCESS, "E", "PARK-A", "A2");

        assertThat(here.reference()).isNotEqualTo(elsewhere.reference());
        // The token carries its own location, so it round-trips even when the owning
        // alert is attributed to a different park or building.
        assertThat(SecurityEventIdentity.fromReference(here.reference(), "OTHER-PARK", "B9")).isEqualTo(here);
    }

    @Test
    void rejectsAMalformedLocationSuffixInsteadOfTreatingItAsLocationLess() {
        // The building declares length 2 but carries one character, so the location suffix is
        // damaged. It must not silently pass as the supported location-less token.
        String truncated = "security-event:source:14#ACCESS_CONTROL:8#access-1:1#E:6#PARK-A:2#A";

        assertThat(SecurityEventIdentity.canonicalQualifiedReference(truncated)).isNull();
        assertThat(SecurityEventIdentity.parseQualifiedReference(truncated)).isNull();
        assertThatThrownBy(() -> SecurityEventIdentity.fromReference(truncated, "PARK-A", "A1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location");
    }

    @Test
    void keepsSourcePrefixedEventIdsThatDoNotDecodeAsQualifiedReferences() {
        SecurityEventIdentity identity = identity(SecuritySourceRef.unknown(), "source:not-encoded");

        assertThat(identity.reference()).isEqualTo("security-event:source:not-encoded");
        assertThat(SecurityEventIdentity.fromReference(identity.reference(), "PARK-A", "A1").eventId())
                .isEqualTo("source:not-encoded");
    }
}
