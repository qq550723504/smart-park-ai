package com.example.smartpark.web;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.adapter.mock.MockKnowledgeAdapter;
import com.example.smartpark.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeAdminControllerTest {

    private final MockKnowledgeAdapter knowledge = new MockParkFixture().knowledge();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new KnowledgeAdminController(knowledge, new AuditTrail()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void adminCanCreateThenActivateAndDeactivateKnowledgeThroughHttpWithoutExposingContent() throws Exception {
        String body = """
                {
                  "id":"KD-HTTP-001",
                  "title":"HTTP integration playbook",
                  "content":"private searchable body: reset the equipment safely",
                  "tags":["http","integration"]
                }
                """;

        mockMvc.perform(post("/api/knowledge")
                        .header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("KD-HTTP-001"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private searchable body"))));
        assertThat(knowledge.search("integration")).extracting(document -> document.id())
                .contains("KD-HTTP-001");

        mockMvc.perform(patch("/api/knowledge/KD-HTTP-001/active")
                        .header("X-Demo-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(knowledge.search("integration")).extracting(document -> document.id())
                .doesNotContain("KD-HTTP-001");

        mockMvc.perform(get("/api/knowledge").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'KD-HTTP-001')].active").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private searchable body"))));
    }

    @Test
    void knowledgeManagementRequiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/knowledge").header("X-Demo-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/knowledge")
                        .header("X-Demo-Role", "CUSTOMER_AGENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"KD-HTTP-002\",\"title\":\"Title\",\"content\":\"Body\",\"tags\":[\"tag\"]}"))
                .andExpect(status().isForbidden());
    }
}
