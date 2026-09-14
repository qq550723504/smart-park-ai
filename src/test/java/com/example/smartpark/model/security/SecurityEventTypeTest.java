package com.example.smartpark.model.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SecurityEventTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "FIRE_SMOKE", "PERIMETER_INTRUSION", "CROWDING", "POST_ABSENCE", "ACCESS_ANOMALY", "UNKNOWN"
    })
    void mapsCanonicalNamesToThemselves(String canonical) {
        assertThat(SecurityEventType.fromRaw(canonical)).isEqualTo(SecurityEventType.valueOf(canonical));
    }

    @ParameterizedTest
    @CsvSource({
            "fire, FIRE_SMOKE",
            "smoke, FIRE_SMOKE",
            "smoke_detected, FIRE_SMOKE",
            "intrusion, PERIMETER_INTRUSION",
            "perimeter, PERIMETER_INTRUSION",
            "boundary_crossing, PERIMETER_INTRUSION",
            "crowd, CROWDING",
            "gathering, CROWDING",
            "person_gathering, CROWDING",
            "absence, POST_ABSENCE",
            "off_post, POST_ABSENCE",
            "post_abandoned, POST_ABSENCE",
            "unauthorized_access_attempt, ACCESS_ANOMALY",
            "unauthorized_access, ACCESS_ANOMALY",
            "access_denied, ACCESS_ANOMALY"
    })
    void mapsKnownAliases(String raw, String expected) {
        assertThat(SecurityEventType.fromRaw(raw)).isEqualTo(SecurityEventType.valueOf(expected));
    }

    @Test
    void mapsVendorAliasesCaseInsensitivelyAndTrims() {
        assertThat(SecurityEventType.fromRaw("  Fire_Smoke  ")).isEqualTo(SecurityEventType.FIRE_SMOKE);
        assertThat(SecurityEventType.fromRaw("off_post")).isEqualTo(SecurityEventType.POST_ABSENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "VENDOR_PRIVATE_CODE_42",
            "thermal_anomaly",
            "unknown",
            "0",
            "   ",
            "\t"
    })
    void fallsBackToUnknownForUnmappedInputs(String raw) {
        assertThat(SecurityEventType.fromRaw(raw)).isEqualTo(SecurityEventType.UNKNOWN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void fallsBackToUnknownForMissingInput(String raw) {
        assertThat(SecurityEventType.fromRaw(raw)).isEqualTo(SecurityEventType.UNKNOWN);
    }

    @Test
    void neverThrowsForArbitraryInput() {
        assertThatCode(() -> SecurityEventType.fromRaw("\u0000*/\u4e00\u4e8c"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN"})
    void mapsCanonicalSeverities(String canonical) {
        assertThat(SecurityEventSeverity.fromRaw(canonical))
                .isEqualTo(SecurityEventSeverity.valueOf(canonical));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sev-1", "criticality", "  "})
    void fallsBackToUnknownSeverity(String raw) {
        assertThat(SecurityEventSeverity.fromRaw(raw)).isEqualTo(SecurityEventSeverity.UNKNOWN);
    }
}
