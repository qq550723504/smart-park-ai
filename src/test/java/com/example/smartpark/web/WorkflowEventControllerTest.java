package com.example.smartpark.web;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowEventController.class)
@Import(ApiExceptionHandler.class)
class WorkflowEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertWorkflow workflow;

    @MockitoBean
    private WorkflowEventPublisher eventPublisher;

    @Test
    void eventStreamUsesSafeDtosAndCompletesAtTerminalEventWhenPublisherStaysOpen() throws Exception {
        String workflowId = "wf-events";
        when(workflow.status(workflowId)).thenReturn(snapshot(workflowId));
        when(eventPublisher.events(workflowId)).thenReturn(Flux.concat(
                Flux.just(
                        new WorkflowEvent(
                                workflowId,
                                1,
                                WorkflowEvent.EventType.NODE_STARTED,
                                "diagnoseAlert",
                                Instant.parse("2026-08-23T01:45:00Z"),
                                "apiKey=secret-value model started"),
                        new WorkflowEvent(
                                workflowId,
                                2,
                                WorkflowEvent.EventType.COMPLETED,
                                "workflow",
                                Instant.parse("2026-08-23T01:46:00Z"),
                                "workflow completed")),
                Flux.never()));

        MvcResult async = mockMvc.perform(get("/api/workflows/{workflowId}/events", workflowId))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(containsString("id:1")))
                .andExpect(content().string(containsString("event:NODE_STARTED")))
                .andExpect(content().string(containsString("\"eventId\":\"1\"")))
                .andExpect(content().string(containsString("\"type\":\"NODE_STARTED\"")))
                .andExpect(content().string(containsString("\"node\":\"diagnoseAlert\"")))
                .andExpect(content().string(containsString("\"sequence\":1")))
                .andExpect(content().string(containsString("\"timestamp\":\"2026-08-23T01:45:00Z\"")))
                .andExpect(content().string(containsString(
                        "\"redactedSummary\":\"[REDACTED]\"")))
                .andExpect(content().string(not(containsString("secret-value"))))
                .andExpect(content().string(not(containsString("workflowId"))))
                .andExpect(content().string(not(containsString("statePayload"))));
    }

    @Test
    void observabilityAggregatesOnlySafeWorkflowEvents() throws Exception {
        String workflowId = "wf-events";
        when(workflow.status(workflowId)).thenReturn(snapshot(workflowId));
        when(eventPublisher.history(workflowId)).thenReturn(List.of(
                new WorkflowEvent(workflowId, 1, WorkflowEvent.EventType.TOOL_CALLED, "retrieveKnowledge",
                        Instant.parse("2026-08-23T01:45:00Z"), "KnowledgePort.search"),
                new WorkflowEvent(workflowId, 2, WorkflowEvent.EventType.FAILED, "retrieveKnowledge",
                        Instant.parse("2026-08-23T01:46:00Z"), "KNOWLEDGE_RETRIEVAL_FAILED")));

        mockMvc.perform(get("/api/workflows/{workflowId}/observability", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(2))
                .andExpect(jsonPath("$.toolCalls").value(1))
                .andExpect(jsonPath("$.tools[0]").value("KnowledgePort.search"))
                .andExpect(jsonPath("$.failedNodes[0]").value("retrieveKnowledge"));
    }
    @Test
    void eventsForUnknownWorkflowReturnNotFoundWithoutOpeningAStream() throws Exception {
        when(workflow.status("wf-missing"))
                .thenThrow(new NoSuchElementException("Unknown workflow: wf-missing"));

        mockMvc.perform(get("/api/workflows/wf-missing/events"))
                .andExpect(status().isNotFound());

        verify(eventPublisher, never()).events("wf-missing");
    }

    private static WorkflowSnapshot snapshot(String workflowId) {
        return new WorkflowSnapshot(
                workflowId,
                "ALT-TEMP-001",
                WorkflowStatus.COMPLETED,
                Map.of(),
                null,
                Optional.empty(),
                null,
                List.of(),
                2);
    }
}
