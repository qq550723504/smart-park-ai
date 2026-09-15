package com.example.smartpark.web;

import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentPage;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import com.example.smartpark.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIncidentControllerTest {
    private final SecurityIncidentService service = mock(SecurityIncidentService.class);
    private final AuditTrail auditTrail = new AuditTrail();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SecurityIncidentController(service, auditTrail))
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
    void reportsWhetherAnIncidentsDispositionComesFromAProductionFeed() throws Exception {
        when(service.list(any())).thenReturn(new SecurityIncidentPage(List.of(incident()), 1));
        when(service.dispositionIsProductionBacked(any())).thenReturn(true);

        // The UI must be able to tell a production-backed false positive apart from a manual
        // review of a demo incident before it publishes a queue-wide statistic.
        mockMvc.perform(get("/api/security/incidents?limit=20")
                        .header("X-Demo-Role", "ADMIN").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].dispositionProduction").value(true));
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
        when(service.applyReview(eq("INC-1"), eq(SecurityDisposition.CONFIRMED_INCIDENT), eq("ADMIN")))
                .thenReturn(new SecurityIncidentService.ReviewOutcome(reviewedIncident(), true));
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

        assertThat(auditTrail.entries()).extracting(entry -> entry.action() + "=" + entry.outcome())
                .containsExactly("REVIEW_SECURITY_INCIDENT=SUCCESS:FALSE_POSITIVE",
                        "HANDOFF_SECURITY_INCIDENT=SUCCESS");
    }

    @Test
    void auditsAnIdempotentReviewAsANoChangeWithThePersistedDisposition() throws Exception {
        when(service.applyReview(eq("INC-1"), eq(SecurityDisposition.CONFIRMED_INCIDENT), eq("APPROVER")))
                .thenReturn(new SecurityIncidentService.ReviewOutcome(reviewedIncident(), false));

        mockMvc.perform(post("/api/security/incidents/INC-1/review").header("X-Demo-Role", "APPROVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("FALSE_POSITIVE"));

        assertThat(auditTrail.entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actorRole()).isEqualTo("APPROVER");
                    assertThat(entry.action()).isEqualTo("REVIEW_SECURITY_INCIDENT");
                    assertThat(entry.outcome()).isEqualTo("NO_CHANGE:FALSE_POSITIVE");
                });
    }

    @Test
    void forwardsAnExplicitHumanDispositionFromTheReviewBody() throws Exception {
        when(service.applyReview(eq("INC-1"), eq(SecurityDisposition.FALSE_POSITIVE), eq("APPROVER")))
                .thenReturn(new SecurityIncidentService.ReviewOutcome(reviewedIncident(), true));

        mockMvc.perform(post("/api/security/incidents/INC-1/review")
                        .header("X-Demo-Role", "APPROVER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"FALSE_POSITIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.dispositionSource").value("HUMAN_REVIEW"))
                .andExpect(jsonPath("$.dispositionDecidedAt").value("2026-09-02T08:05:00Z"));
    }

    @Test
    void rejectsUnsupportedOrUnreviewedDispositions() throws Exception {
        mockMvc.perform(post("/api/security/incidents/INC-1/review")
                        .header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"AI_GUESS\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/security/incidents/INC-1/review")
                        .header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disposition\":\"UNREVIEWED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailWhitelistsSafeDispositionAndEventMetadata() throws Exception {
        when(service.get("INC-1")).thenReturn(reviewedIncident());

        mockMvc.perform(get("/api/security/incidents/INC-1").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("ACCESS_ANOMALY"))
                .andExpect(jsonPath("$.disposition").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.evidence[0].rawEventType").value("UNAUTHORIZED_ACCESS_ATTEMPT"))
                .andExpect(jsonPath("$.evidence[0].sourceType").value("ACCESS_CONTROL"))
                .andExpect(jsonPath("$.evidence[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.evidence[0].confidence").value(0.6))
                .andExpect(jsonPath("$.prompt").doesNotExist())
                .andExpect(jsonPath("$.modelResponse").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.evidence[0].prompt").doesNotExist());
    }

    @Test
    void mapsLegacyEventTypesToTheStandardTypeInSummaries() throws Exception {
        SecurityIncident legacy = new SecurityIncident("INC-1", "PARK-A", "A1", "UNAUTHORIZED_ACCESS_ATTEMPT",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.OPEN, Instant.parse("2026-09-02T08:00:00Z"),
                Instant.parse("2026-09-02T08:00:00Z"), List.of("SEC-1"), List.of(), List.of(), List.of(), List.of(),
                null, null);
        when(service.get("INC-1")).thenReturn(legacy);

        mockMvc.perform(get("/api/security/incidents/INC-1").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("ACCESS_ANOMALY"));
    }

    private static SecurityIncident reviewedIncident() {
        Instant at = Instant.parse("2026-09-02T08:05:00Z");
        SecurityDispositionRecord record = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.HUMAN_REVIEW, "APPROVER", null, null, "human-review:INC-1", at);
        return new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS_ANOMALY", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.REVIEWED, Instant.parse("2026-09-02T08:00:00Z"), at, List.of("SEC-1"),
                List.of("ALT-1"), List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe",
                        "UNAUTHORIZED_ACCESS_ATTEMPT", "ACCESS_CONTROL", "demo-access", "MEDIUM", 0.6d)),
                List.of(), List.of("核对安全处置手册。"), at, null,
                SecurityDisposition.FALSE_POSITIVE, record);
    }

    private static SecurityIncident incident() {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.OPEN, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe")), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }
}
