package com.example.smartpark.web;

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

    @RestController
    static class OverloadedController {
        @PostMapping("/test-overloaded")
        void start() {
            throw new java.util.concurrent.RejectedExecutionException("queue is full");
        }
    }
}
