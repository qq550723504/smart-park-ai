package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventCapabilityRegistryTest {

    @Test
    void modelSupportsAllStandardTypes() {
        SecurityEventCapabilityRegistry registry = registry();

        assertThat(registry.modelSupportedTypes()).containsExactlyInAnyOrder(SecurityEventType.values());
    }

    @Test
    void reportsNotReadyForTypesWithoutAnyAdapter() {
        List<SecurityEventCapability> capabilities = registry().capabilities();

        assertThat(capabilities)
                .filteredOn(capability -> capability.eventType() == SecurityEventType.FIRE_SMOKE)
                .singleElement()
                .satisfies(capability -> {
                    assertThat(capability.modelSupported()).isTrue();
                    assertThat(capability.sourceConnected()).isFalse();
                    assertThat(capability.state()).isEqualTo(SecurityEventCapability.State.NOT_READY);
                });
        assertThat(capabilities)
                .filteredOn(capability -> capability.eventType() != SecurityEventType.ACCESS_ANOMALY)
                .allSatisfy(capability -> assertThat(capability.state())
                        .isEqualTo(SecurityEventCapability.State.NOT_READY));
    }

    @Test
    void reportsAdaptedForANonProductionSource() {
        SecurityEventCapability capability = capabilityFor(registry(demoAdapter(
                SecurityEventType.ACCESS_ANOMALY)), SecurityEventType.ACCESS_ANOMALY);

        assertThat(capability.sourceConnected()).isTrue();
        assertThat(capability.productionSource()).isFalse();
        assertThat(capability.state()).isEqualTo(SecurityEventCapability.State.ADAPTED);
    }

    @Test
    void reportsAvailableOnlyForAProductionSource() {
        SecurityEventCapability capability = capabilityFor(registry(productionAdapter(
                SecurityEventType.FIRE_SMOKE)), SecurityEventType.FIRE_SMOKE);

        assertThat(capability.productionSource()).isTrue();
        assertThat(capability.state()).isEqualTo(SecurityEventCapability.State.AVAILABLE);
    }

    @Test
    void unionsSourcesAcrossAdaptersAndPrefersProduction() {
        SecurityEventCapability capability = capabilityFor(registry(
                demoAdapter(SecurityEventType.PERIMETER_INTRUSION),
                productionAdapter(SecurityEventType.PERIMETER_INTRUSION)), SecurityEventType.PERIMETER_INTRUSION);

        assertThat(capability.sourceConnected()).isTrue();
        assertThat(capability.productionSource()).isTrue();
        assertThat(capability.state()).isEqualTo(SecurityEventCapability.State.AVAILABLE);
    }

    @Test
    void neverClaimsUnknownTypeIsConnected() {
        SecurityEventCapability capability = capabilityFor(registry(demoAdapter(
                SecurityEventType.ACCESS_ANOMALY)), SecurityEventType.UNKNOWN);

        assertThat(capability.sourceConnected()).isFalse();
        assertThat(capability.state()).isEqualTo(SecurityEventCapability.State.NOT_READY);
    }

    @Test
    void enablesDispositionOnlyWithAnExplicitProductionDispositionFeed() {
        assertThat(registry(demoAdapter(SecurityEventType.ACCESS_ANOMALY)).dispositionEnabled()).isFalse();
        assertThat(registry().dispositionEnabled()).isFalse();
        assertThat(registry(productionAdapter(SecurityEventType.FIRE_SMOKE)).dispositionEnabled()).isFalse();
        assertThat(registry(productionDispositionAdapter(SecurityEventType.FIRE_SMOKE)).dispositionEnabled()).isTrue();
    }

    @Test
    void capabilitiesAreImmutableAndFollowDeclarationOrder() {
        List<SecurityEventCapability> capabilities = registry().capabilities();

        assertThat(capabilities).extracting(SecurityEventCapability::eventType)
                .containsExactly(SecurityEventType.values());
        assertThatThrownBy(() -> capabilities.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnsafeSourceIdentifiers() {
        assertThatThrownBy(() -> new SecuritySourceDescriptor(
                "rtsp://user:pass@cam", SecuritySourceType.CAMERA_ANALYTICS,
                Set.of(SecurityEventType.FIRE_SMOKE), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId");
    }

    private static SecurityEventCapability capabilityFor(SecurityEventCapabilityRegistry registry,
                                                         SecurityEventType type) {
        return registry.capabilities().stream()
                .filter(capability -> capability.eventType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static SecurityEventCapabilityRegistry registry(SecuritySourceAdapter... adapters) {
        return new SecurityEventCapabilityRegistry(List.of(adapters));
    }

    private static SecuritySourceAdapter demoAdapter(SecurityEventType... types) {
        return adapter("demo-access-feed", SecuritySourceType.ACCESS_CONTROL, false, types);
    }

    private static SecuritySourceAdapter productionAdapter(SecurityEventType... types) {
        return adapter("prod-camera-analytics", SecuritySourceType.CAMERA_ANALYTICS, true, false, types);
    }

    private static SecuritySourceAdapter productionDispositionAdapter(SecurityEventType... types) {
        return adapter("prod-disposition-feed", SecuritySourceType.CAMERA_ANALYTICS, true, true, types);
    }

    private static SecuritySourceAdapter adapter(String sourceId, SecuritySourceType sourceType,
                                                 boolean production, SecurityEventType... types) {
        return adapter(sourceId, sourceType, production, false, types);
    }

    private static SecuritySourceAdapter adapter(String sourceId, SecuritySourceType sourceType,
                                                 boolean production, boolean dispositionFeed,
                                                 SecurityEventType... types) {
        SecuritySourceDescriptor descriptor = new SecuritySourceDescriptor(
                sourceId, sourceType, Set.of(types), production, dispositionFeed);
        return new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return List.of();
            }
        };
    }
}
