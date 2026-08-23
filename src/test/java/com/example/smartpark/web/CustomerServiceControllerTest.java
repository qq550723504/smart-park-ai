package com.example.smartpark.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerServiceControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new CustomerServiceController(new CustomerServiceWorkflow(new MockParkFixture().knowledge())))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void invalidQuestionReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/customer-service/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSessionReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/customer-service/sessions/cs-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void idempotencyKeyCanBeRetriedWithoutCreatingAnotherSession() throws Exception {
        String body = "{\"question\":\"A1 洗手间漏水，需要报修\"}";

        String first = mockMvc.perform(post("/api/customer-service/sessions")
                        .header("Idempotency-Key", "customer-request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/customer-service/sessions")
                        .header("Idempotency-Key", "customer-request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(org.hamcrest.Matchers.containsString("cs-")))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).isEqualTo(first));
    }

    @Test
    void idempotencyConflictExplainsThatTheKeyWasReused() throws Exception {
        String key = "customer-request-conflict";
        String first = mockMvc.perform(post("/api/customer-service/sessions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客停车怎么收费？\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = new ObjectMapper().readTree(first).get("sessionId").asText();

        mockMvc.perform(post("/api/customer-service/sessions/" + sessionId + "/messages")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客如何预约进入园区？\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 已用于其他问题，请生成新的请求键"));
    }
}
