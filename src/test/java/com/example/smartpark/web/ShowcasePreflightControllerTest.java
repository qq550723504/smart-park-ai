package com.example.smartpark.web;

import com.example.smartpark.showcase.InMemoryScenarioVerificationRegistry;
import com.example.smartpark.showcase.ScenarioVerificationRegistry;
import com.example.smartpark.showcase.ShowcasePreflightProbe;
import com.example.smartpark.showcase.ShowcasePreflightReport;
import com.example.smartpark.showcase.ShowcasePreflightService;
import com.example.smartpark.showcase.ShowcaseProbeResult;
import com.example.smartpark.showcase.ShowcaseScenarioId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShowcasePreflightController.class)
@Import(ShowcasePreflightControllerTest.PreflightFixture.class)
class ShowcasePreflightControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsMissingOrNonAdminRole() throws Exception {
        mockMvc.perform(post("/api/showcase/preflight"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/showcase/preflight").header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsOnlySafeResultsForAdmin() throws Exception {
        String responseBody = mockMvc.perform(post("/api/showcase/preflight")
                        .header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].scenarioId").value("OPERATIONS_ANALYSIS"))
                .andExpect(jsonPath("$.results[0].status").value("NOT_READY"))
                .andExpect(jsonPath("$.results[0].reason").value("在线验证未通过"))
                .andExpect(jsonPath("$.results[0].verifiedAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody).doesNotContain("provider-secret-body");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PreflightFixture {

        @Bean
        ScenarioVerificationRegistry scenarioVerificationRegistry() {
            return new InMemoryScenarioVerificationRegistry();
        }

        @Bean
        @Qualifier("showcaseClock")
        Clock showcaseClock() {
            return Clock.fixed(Instant.parse("2026-08-31T10:00:00Z"), ZoneOffset.UTC);
        }

        @Bean(destroyMethod = "shutdownNow")
        ExecutorService showcasePreflightExecutor() {
            return Executors.newSingleThreadExecutor();
        }

        @Bean
        ShowcasePreflightProbe failingOperationsAnalysisProbe() {
            return new ShowcasePreflightProbe() {
                @Override
                public ShowcaseScenarioId scenarioId() {
                    return ShowcaseScenarioId.OPERATIONS_ANALYSIS;
                }

                @Override
                public ShowcaseProbeResult probe() {
                    throw new IllegalStateException("provider-secret-body");
                }
            };
        }

        @Bean
        ShowcasePreflightService showcasePreflightService(
                ScenarioVerificationRegistry registry,
                @Qualifier("showcaseClock") Clock clock,
                ExecutorService showcasePreflightExecutor,
                ShowcasePreflightProbe failingOperationsAnalysisProbe) {
            return new ShowcasePreflightService(
                    registry,
                    clock,
                    Duration.ofSeconds(1),
                    showcasePreflightExecutor,
                    List.of(failingOperationsAnalysisProbe));
        }
    }
}
