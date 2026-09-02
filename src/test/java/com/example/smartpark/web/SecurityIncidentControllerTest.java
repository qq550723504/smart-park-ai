package com.example.smartpark.web;

import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentPage;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIncidentControllerTest {
    private final SecurityIncidentService service = mock(SecurityIncidentService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SecurityIncidentController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void adminCanReadSafeIncidentSummary() throws Exception {
        when(service.list(any())).thenReturn(new SecurityIncidentPage(List.of(incident()), 1));

        mockMvc.perform(get("/api/security/incidents?limit=20")
                        .header("X-Demo-Role", "ADMIN").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value("INC-1"))
                .andExpect(jsonPath("$.items[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.items[0].summary").value("REDACTED: safe"));
    }

    @Test
    void customerAgentCannotReadSecurityIncidents() throws Exception {
        mockMvc.perform(get("/api/security/incidents").header("X-Demo-Role", "CUSTOMER_AGENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validatesFiltersAndSupportsReviewAndHandoff() throws Exception {
        when(service.list(any())).thenReturn(new SecurityIncidentPage(List.of(incident()), 1));
        when(service.get("INC-1")).thenReturn(incident());
        when(service.review("INC-1")).thenReturn(incident());
        when(service.handoff("INC-1")).thenReturn(incident());

        mockMvc.perform(get("/api/security/incidents?limit=101").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/security/incidents?status=UNKNOWN").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/security/incidents/INC-1").header("X-Demo-Role", "APPROVER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidence[0].summary").value("REDACTED: safe"));
        mockMvc.perform(post("/api/security/incidents/INC-1/review").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/security/incidents/INC-1/handoff").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    private static SecurityIncident incident() {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.OPEN, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe")), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }
}
