package com.example.smartpark.web;

import com.example.smartpark.orchestration.OrchestrationCapacityException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    @Test
    void mapsBoundedExecutorRejectionToExplicitOverloadBackpressure() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OverloadedController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/test-overloaded"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many collaboration runs; retry later"));
    }

    @Test
    void mapsOrchestrationAdmissionRejectionToExplicitOverloadBackpressure() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OrchestrationOverloadedController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/test-orchestration-overloaded"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many orchestration runs; retry later"));
    }

    @RestController
    static class OverloadedController {
        @PostMapping("/test-overloaded")
        void start() {
            throw new java.util.concurrent.RejectedExecutionException("queue is full");
        }
    }

    @RestController
    static class OrchestrationOverloadedController {
        @PostMapping("/test-orchestration-overloaded")
        void start() {
            throw new OrchestrationCapacityException("active capacity exhausted");
        }
    }
}
