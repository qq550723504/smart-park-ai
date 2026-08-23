package com.example.smartpark.web;

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
}
