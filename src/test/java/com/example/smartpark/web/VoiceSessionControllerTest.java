package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoiceSessionControllerTest {

    @SuppressWarnings("unused")
    private static final java.util.List<String> LIST_ANCHOR = java.util.List.of();

    private final VoiceTestSupport support =
            new VoiceTestSupport("{\"intent\":\"CHITCHAT\"}", List.of());

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new VoiceSessionController(support.service))
            .build();

    @Test
    void createReturnsSessionIdAndWebSocketPath() throws Exception {
        mockMvc.perform(post("/api/voice/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.wsPath").value(
                        org.hamcrest.Matchers.startsWith("/ws/voice/sessions/vs-")));
    }

    @Test
    void describeReturnsSnapshotForKnownSession() throws Exception {
        var created = support.service.createSession(null);

        mockMvc.perform(get("/api/voice/sessions/" + created.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(created.sessionId()))
                .andExpect(jsonPath("$.runId").isNotEmpty());
    }

    @Test
    void unknownSessionReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/voice/sessions/vs-missing"))
                .andExpect(status().isNotFound());
    }
}
