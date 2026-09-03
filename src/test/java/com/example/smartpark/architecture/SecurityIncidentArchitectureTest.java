package com.example.smartpark.architecture;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIncidentArchitectureTest {
    @Test
    void serviceDependsOnCapabilityPortsInsteadOfAdaptersOrWebClasses() {
        assertThat(SecurityIncidentService.class.getPackageName()).isEqualTo("com.example.smartpark.securityincident");
        assertThat(SecurityIncidentHandoffPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.collaboration");
        assertThat(SecurityIncidentService.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(field.getType().getName())
                        .doesNotContain("adapter.mock", ".web.", "device"));
    }
}
