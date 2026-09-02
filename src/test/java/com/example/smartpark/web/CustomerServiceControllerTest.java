package com.example.smartpark.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerServiceControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new CustomerServiceController(new CustomerServiceWorkflow(new MockParkFixture().knowledge())))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void successfulCustomerRequestReturnsExecutionRunHeaderWithoutChangingJson() throws Exception {
        var publisher = new InMemoryExecutionEventPublisher();
        var controller = new CustomerServiceController(
                new CustomerServiceWorkflow(new MockParkFixture().knowledge()), new AuditTrail(), publisher);
        MockMvc observedMockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        observedMockMvc.perform(post("/api/customer-service/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客停车怎么收费？\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Execution-Run-Id"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.executionRunId").doesNotExist());
    }

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

    @Test
    void initialKnowledgeSearchFailureReturnsSafeHumanHandoffInsteadOfServerError() throws Exception {
        String secret = "providerResponse=private-knowledge-body";
        KnowledgePort failingKnowledge = (domain, query) -> {
            throw new IllegalStateException(secret);
        };
        MockMvc failingMockMvc = MockMvcBuilders.standaloneSetup(
                        new CustomerServiceController(new CustomerServiceWorkflow(failingKnowledge)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        failingMockMvc.perform(post("/api/customer-service/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客停车怎么收费？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isString())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))))
                .andExpect(jsonPath("$.needsHuman").value(true))
                .andExpect(jsonPath("$.ticket.status").value("WAITING_AGENT"));
    }

    @Test
    void followUpKnowledgeSearchFailureReturnsSafeHumanHandoffInsteadOfServerError() throws Exception {
        String secret = "raw knowledge body and exception token";
        KnowledgePort failingKnowledge = new KnowledgePort() {
            private int calls;

            @Override
            public java.util.List<com.example.smartpark.model.common.KnowledgeDocument> search(KnowledgeDomain domain, String query) {
                if (++calls == 1) return new MockParkFixture().knowledge().search(domain, query);
                throw new IllegalStateException(secret);
            }

            @Override
            public java.util.List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
                return search(domain, query).stream()
                        .map(document -> new KnowledgeMatch(document, 1.0))
                        .toList();
            }
        };
        MockMvc failingMockMvc = MockMvcBuilders.standaloneSetup(
                        new CustomerServiceController(new CustomerServiceWorkflow(failingKnowledge)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        String first = failingMockMvc.perform(post("/api/customer-service/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客停车怎么收费？\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = new ObjectMapper().readTree(first).get("sessionId").asText();

        failingMockMvc.perform(post("/api/customer-service/sessions/" + sessionId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客如何预约进入园区？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isString())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))))
                .andExpect(jsonPath("$.needsHuman").value(true))
                .andExpect(jsonPath("$.ticket.status").value("WAITING_AGENT"));
    }

    @Test
    void repairFollowUpCreatesWaitingAgentTicket() throws Exception {
        String first = mockMvc.perform(post("/api/customer-service/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"访客停车怎么收费？\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = new ObjectMapper().readTree(first).get("sessionId").asText();

        mockMvc.perform(post("/api/customer-service/sessions/" + sessionId + "/messages")
                        .header("Idempotency-Key", "repair-follow-up-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"A1 洗手间漏水，需要报修\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("REPAIR"))
                .andExpect(jsonPath("$.needsHuman").value(true))
                .andExpect(jsonPath("$.ticket.status").value("WAITING_AGENT"));
    }
}
