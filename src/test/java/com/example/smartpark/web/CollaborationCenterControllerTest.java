package com.example.smartpark.web;

import com.example.smartpark.collaborationcenter.CollaborationCenterService;
import com.example.smartpark.collaborationcenter.CollaborationWorkItem;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollaborationCenterControllerTest {

    private final CollaborationCenterService service = mock(CollaborationCenterService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new CollaborationCenterController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void adminCanReadSafeWorkItems() throws Exception {
        when(service.list(any())).thenReturn(List.of(alertItem()));

        mockMvc.perform(get("/api/collaboration/work-items")
                        .header("X-Demo-Role", "ADMIN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ALERT_WORKFLOW:wf-1"))
                .andExpect(jsonPath("$[0].source").value("ALERT_WORKFLOW"))
                .andExpect(jsonPath("$[0].safeSummary").value("告警 ALT-POWER-001 · A2 · DEV-POWER-001"))
                .andExpect(jsonPath("$[0].openedAt").value("2026-09-01T08:00:00Z"))
                .andExpect(jsonPath("$[0].slaDueAt").value("2026-09-01T08:30:00Z"))
                .andExpect(jsonPath("$[0].slaState").value("DUE_SOON"))
                .andExpect(jsonPath("$[0].diagnosis").doesNotExist())
                .andExpect(jsonPath("$[0].approval").doesNotExist());
    }

    @Test
    void viewerCannotReadWorkItems() throws Exception {
        mockMvc.perform(get("/api/collaboration/work-items")
                        .header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidFiltersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/collaboration/work-items?source=UNKNOWN")
                        .header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/collaboration/work-items?limit=51")
                        .header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    private static CollaborationWorkItem alertItem() {
        return new CollaborationWorkItem(
                "ALERT_WORKFLOW:wf-1", CollaborationWorkItem.Source.ALERT_WORKFLOW,
                CollaborationWorkItem.Status.WAITING_APPROVAL, CollaborationWorkItem.Priority.HIGH,
                "告警处置 ALT-POWER-001", "告警 ALT-POWER-001 · A2 · DEV-POWER-001",
                "PARK-A", "A2", "DEV-POWER-001", Instant.parse("2026-09-01T08:30:00Z"),
                Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-09-01T08:30:00Z"),
                CollaborationWorkItem.SlaState.DUE_SOON, "workflow");
    }
}
