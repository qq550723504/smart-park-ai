package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
