package com.example.smartpark.web;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.security.SecurityEventCapabilityRegistry;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.port.security.SecuritySourceDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityCapabilityControllerTest {

    @Test
    void approverSeesEveryTypeAsNotReadyWhenNoSourceIsConnected() throws Exception {
        MockMvc mockMvc = mockMvc(registry());

        mockMvc.perform(get("/api/security/capabilities").header("X-Demo-Role", "APPROVER")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(SecurityEventType.values().length))
                .andExpect(jsonPath("$.types[0].eventType").value("FIRE_SMOKE"))
                .andExpect(jsonPath("$.types[0].modelSupported").value(true))
                .andExpect(jsonPath("$.types[0].sourceConnected").value(false))
                .andExpect(jsonPath("$.types[0].state").value("NOT_READY"))
                .andExpect(jsonPath("$.dispositionEnabled").value(false));
    }

    @Test
    void reportsADemoSourceAsAdaptedButNeverAvailable() throws Exception {
        MockMvc mockMvc = mockMvc(registry(adapter(false, SecurityEventType.ACCESS_ANOMALY)));

        mockMvc.perform(get("/api/security/capabilities").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[4].eventType").value("ACCESS_ANOMALY"))
                .andExpect(jsonPath("$.types[4].productionSource").value(false))
                .andExpect(jsonPath("$.types[4].state").value("ADAPTED"))
                .andExpect(jsonPath("$.dispositionEnabled").value(false));
    }

    @Test
    void reportsAProductionSourceAsAvailableAndEnablesDisposition() throws Exception {
        MockMvc mockMvc = mockMvc(registry(adapter(true, true, SecurityEventType.FIRE_SMOKE)));

        mockMvc.perform(get("/api/security/capabilities").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].state").value("AVAILABLE"))
                .andExpect(jsonPath("$.dispositionEnabled").value(true));
    }

    @Test
    void keepsDispositionDisabledForAProductionSourceWithoutADispositionFeed() throws Exception {
        MockMvc mockMvc = mockMvc(registry(adapter(true, false, SecurityEventType.FIRE_SMOKE)));

        mockMvc.perform(get("/api/security/capabilities").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].state").value("AVAILABLE"))
                .andExpect(jsonPath("$.dispositionEnabled").value(false));
    }

    @Test
    void rejectsNonReviewerRoles() throws Exception {
        mockMvc(registry()).perform(get("/api/security/capabilities").header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc(registry()).perform(get("/api/security/capabilities"))
                .andExpect(status().isForbidden());
    }

    private static MockMvc mockMvc(SecurityEventCapabilityRegistry registry) {
        return MockMvcBuilders.standaloneSetup(new SecurityCapabilityController(registry))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static SecurityEventCapabilityRegistry registry(SecuritySourceAdapter... adapters) {
        return new SecurityEventCapabilityRegistry(List.of(adapters));
    }

    private static SecuritySourceAdapter adapter(boolean production, SecurityEventType... types) {
        return adapter(production, false, types);
    }

    private static SecuritySourceAdapter adapter(boolean production, boolean dispositionFeed,
                                                 SecurityEventType... types) {
        SecuritySourceDescriptor descriptor = new SecuritySourceDescriptor(
                production ? "prod-camera-analytics" : "demo-access-feed",
                production ? SecuritySourceType.CAMERA_ANALYTICS : SecuritySourceType.ACCESS_CONTROL,
                Set.of(types), production, dispositionFeed);
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
