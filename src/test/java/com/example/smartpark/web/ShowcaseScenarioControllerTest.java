package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.showcase.InMemoryScenarioVerificationRegistry;
import com.example.smartpark.showcase.ScenarioVerificationRegistry;
import com.example.smartpark.showcase.ShowcaseProperties;
import com.example.smartpark.showcase.ShowcaseScenarioCatalog;
import com.example.smartpark.showcase.ShowcaseScenarioId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShowcaseScenarioController.class)
@Import(ShowcaseScenarioControllerTest.CatalogFixture.class)
class ShowcaseScenarioControllerTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-30T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScenarioVerificationRegistry registry;

    @Autowired
    @Qualifier("showcaseClock")
    private SteppingClock showcaseClock;

    @BeforeEach
    void resetFixture() {
        showcaseClock.reset();
        for (ShowcaseScenarioId id : ShowcaseScenarioId.values()) {
            registry.recordFailure(id);
        }
    }

    @Test
    void returnsCustomerSafeCatalogUsingOneQualifiedInstant() throws Exception {
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, CAPTURED_AT.minusSeconds(1));

        MvcResult result = mockMvc.perform(get("/api/showcase/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAt").value("2026-08-30T10:00:00Z"))
                .andExpect(jsonPath("$.scenarios[0].id").value("ALERT_WORKFLOW"))
                .andExpect(jsonPath("$.scenarios[0].live").value(false))
                .andExpect(jsonPath("$.scenarios[0].unavailableReason")
                        .value("本次部署尚未完成在线验证"))
                .andExpect(jsonPath("$.scenarios[0].launchInput.alertId").value("ALT-POWER-001"))
                .andExpect(jsonPath("$.scenarios[0].launchInput.question").value(nullValue()))
                .andExpect(jsonPath("$.scenarios[1].launchInput.alertId").value(nullValue()))
                .andExpect(jsonPath("$.scenarios[1].launchInput.question").value(
                        "电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联"))
                .andExpect(jsonPath("$.scenarios[2].id").value("OPERATIONS_ANALYSIS"))
                .andExpect(jsonPath("$.scenarios[2].live").value(true))
                .andExpect(jsonPath("$.scenarios[2].lastVerifiedAt")
                        .value("2026-08-30T09:59:59Z"))
                .andExpect(jsonPath("$.scenarios[2].launchInput.alertId").value(nullValue()))
                .andExpect(jsonPath("$.scenarios[2].launchInput.question").value("过去5天各楼宇能耗"))
                .andExpect(jsonPath("$.scenarios[3].launchInput.alertId").value(nullValue()))
                .andExpect(jsonPath("$.scenarios[3].launchInput.question").value("访客停车怎么收费？"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        assertThat(responseBody).doesNotContain("jdbc:", "api-key", "prompt");
    }

    @Test
    void doesNotPublishAFutureVerificationReceipt() throws Exception {
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, CAPTURED_AT.plusSeconds(1));

        mockMvc.perform(get("/api/showcase/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAt").value("2026-08-30T10:00:00Z"))
                .andExpect(jsonPath("$.scenarios[2].status").value("NOT_READY"))
                .andExpect(jsonPath("$.scenarios[2].live").value(false))
                .andExpect(jsonPath("$.scenarios[2].lastVerifiedAt").doesNotExist());
    }

    @Test
    void rejectsWriteRequests() throws Exception {
        mockMvc.perform(post("/api/showcase/scenarios"))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CatalogFixture {

        @Bean
        ScenarioVerificationRegistry scenarioVerificationRegistry() {
            return new InMemoryScenarioVerificationRegistry();
        }

        @Bean
        ShowcaseScenarioCatalog showcaseScenarioCatalog(
                ScenarioVerificationRegistry registry,
                ObjectProvider<ExpertCollaborationService> collaborationProvider) {
            return new ShowcaseScenarioCatalog(
                    registry,
                    new ShowcaseProperties(),
                    "rag",
                    "dashscope",
                    true,
                    true,
                    collaborationProvider);
        }

        @Bean
        @Qualifier("showcaseClock")
        SteppingClock showcaseClock() {
            return new SteppingClock();
        }

        @Bean
        Clock decoyClock() {
            return Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        }
    }

    static final class SteppingClock extends Clock {

        private final AtomicInteger calls = new AtomicInteger();

        void reset() {
            calls.set(0);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(CAPTURED_AT, zone);
        }

        @Override
        public Instant instant() {
            return CAPTURED_AT.plus(Duration.ofMinutes(16L * calls.getAndIncrement()));
        }
    }
}
