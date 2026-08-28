package com.example.smartpark.web;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExecutionEventController.class)
@Import(ApiExceptionHandler.class)
class ExecutionEventControllerTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExecutionEventPublisher publisher;

    @Test
    void sseUsesNamedEventsIdSequenceAndPolymorphicPayloadType() throws Exception {
        List<ExecutionEvent> scripted = List.of(
                event(1, ExecutionEventType.RUN_STARTED, null),
                event(2, ExecutionEventType.TOOL_CALL_COMPLETED,
                        DisplayPayload.toolCall("EnergyQueryTool", java.util.Map.of("buildingId", "B1"))),
                event(3, ExecutionEventType.CHART_SPECIFIED,
                        new DisplayPayload.ChartPayload("STACKED_BAR", "能耗构成", "building_name",
                                List.of("energy_kwh"), "meter_id", "kWh", "HORIZONTAL", true,
                                null, "", "")),
                event(4, ExecutionEventType.COMPLETED, null));
        scriptSubscription(scripted);

        MvcResult async = mockMvc.perform(get("/api/executions/{runId}/events", RUN_ID))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(content().string(containsString("id:1")))
                .andExpect(content().string(containsString("event:RUN_STARTED")))
                .andExpect(content().string(containsString("\"payloadType\":\"TOOL_CALL\"")))
                .andExpect(content().string(containsString("\"toolName\":\"EnergyQueryTool\"")))
                .andExpect(content().string(containsString("\"scenario\":\"ALERT_WORKFLOW\"")))
                .andExpect(content().string(containsString("\"sequence\":4")))
                .andExpect(content().string(containsString("\"type\":\"STACKED_BAR\"")))
                .andExpect(content().string(containsString("\"orientation\":\"HORIZONTAL\"")))
                // credentials must never appear even if a payload carried them
                .andExpect(content().string(not(containsString("apiKey"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void terminalEventClosesTheNamedStream() throws Exception {
        scriptSubscription(List.of(
                event(1, ExecutionEventType.RUN_STARTED, null),
                event(2, ExecutionEventType.FAILED,
                        DisplayPayload.error(ExecutionStage.TOOL_EXECUTION, "TOOL_TIMEOUT", true, "timed out"))));

        MvcResult async = mockMvc.perform(get("/api/executions/{runId}/events", RUN_ID))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:FAILED")))
                .andExpect(content().string(containsString("\"payloadType\":\"ERROR\"")))
                .andExpect(content().string(containsString("\"retryable\":true")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body.indexOf("id:1")).isLessThan(body.indexOf("id:2"));
    }

    @Test
    void unknownRunReturnsNotFoundWithoutOpeningAStream() throws Exception {
        doThrow(new IllegalArgumentException("unknown run " + RUN_ID))
                .when(publisher).subscribe(eq(RUN_ID), any());

        mockMvc.perform(get("/api/executions/{runId}/events", RUN_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void runSummaryExposesStatusAndCountOnly() throws Exception {
        when(publisher.history(RUN_ID)).thenReturn(List.of(
                event(1, ExecutionEventType.RUN_STARTED, null),
                event(2, ExecutionEventType.COMPLETED, null)));
        when(publisher.status(RUN_ID)).thenReturn("COMPLETED");

        mockMvc.perform(get("/api/executions/{runId}", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalEvents").value(2));
    }

    private void scriptSubscription(List<ExecutionEvent> scripted) {
        doAnswer(invocation -> {
            Consumer<ExecutionEvent> consumer = invocation.getArgument(1);
            scripted.forEach(consumer);
            return (ExecutionEventPublisher.Subscription) () -> {};
        }).when(publisher).subscribe(eq(RUN_ID), any());
    }

    private static ExecutionEvent event(long sequence, ExecutionEventType type, DisplayPayload payload) {
        return new ExecutionEvent(UUID.randomUUID(), RUN_ID, sequence,
                Instant.parse("2026-08-24T08:00:00Z").plusSeconds(sequence),
                ExecutionScenario.ALERT_WORKFLOW, "alert workflow",
                ExecutionStage.ANALYSIS, type,
                type == ExecutionEventType.COMPLETED || type == ExecutionEventType.FAILED
                        ? ExecutionStatus.SUCCEEDED
                        : ExecutionStatus.RUNNING,
                "safe summary " + sequence, payload);
    }
}
