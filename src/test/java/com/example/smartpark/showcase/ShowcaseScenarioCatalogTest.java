package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ShowcaseScenarioCatalogTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    private ScenarioVerificationRegistry registry;
    private ShowcaseProperties properties;
    private ShowcaseScenarioCatalog catalog;

    @BeforeEach
    void setUp() {
        registry = new InMemoryScenarioVerificationRegistry();
        properties = new ShowcaseProperties();
        catalog = catalog("rag", "dashscope", true, true, collaborationProvider(true));
    }

    @Test
    void doesNotTreatConfiguredRuntimeAsAReadyShowcase() {
        ShowcaseScenario scenario = scenario(ShowcaseScenarioId.EXPERT_COLLABORATION);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.NOT_READY);
        assertThat(scenario.live()).isFalse();
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署尚未完成在线验证");
        assertThat(scenario.lastVerifiedAt()).isNull();
    }

    @Test
    void makesAConfiguredScenarioReadyOnlyWithAnUnexpiredReceipt() {
        Instant verifiedAt = NOW.minus(Duration.ofMinutes(1));
        registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, verifiedAt);

        ShowcaseScenario scenario = scenario(ShowcaseScenarioId.EXPERT_COLLABORATION);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.READY);
        assertThat(scenario.live()).isTrue();
        assertThat(scenario.unavailableReason()).isNull();
        assertThat(scenario.lastVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void returnsDisabledWithoutDisclosingConfigurationDetails() {
        catalog = catalog("rag", "dashscope", true, false, collaborationProvider(true));
        registry.recordSuccess(ShowcaseScenarioId.VOICE_ASSISTANT, NOW.minusSeconds(1));

        ShowcaseScenario voice = scenario(ShowcaseScenarioId.VOICE_ASSISTANT);

        assertThat(voice.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
        assertThat(voice.live()).isFalse();
        assertThat(voice.unavailableReason()).isEqualTo("本次部署未启用语音体验");
        assertThat(voice.unavailableReason()).doesNotContain("smartpark", "DASHSCOPE", "api-key");
        assertThat(voice.lastVerifiedAt()).isNull();
    }

    @Test
    void returnsScenariosInTheFixedIdentifierOrderWithCustomerSafeCopy() {
        assertThat(catalog.scenarios(NOW))
                .extracting(ShowcaseScenario::id, ShowcaseScenario::title,
                        ShowcaseScenario::expectedDurationSeconds,
                        ShowcaseScenario::proofTypes, ShowcaseScenario::humanBoundary)
                .containsExactly(
                        tuple(ShowcaseScenarioId.ALERT_WORKFLOW, "告警处置", 45,
                                java.util.List.of("告警上下文", "处置知识", "风险闸门"),
                                "高风险处置必须由审批人确认"),
                        tuple(ShowcaseScenarioId.EXPERT_COLLABORATION, "跨域专家协作", 40,
                                java.util.List.of("专家分工", "工具证据", "汇总结论"),
                                "证据不足时保留人工复核"),
                        tuple(ShowcaseScenarioId.OPERATIONS_ANALYSIS, "运营分析", 30,
                                java.util.List.of("指标口径", "只读查询", "结果图表"),
                                "只读数据，不自动执行操作"),
                        tuple(ShowcaseScenarioId.VOICE_ASSISTANT, "实时语音助手", 30,
                                java.util.List.of("语音识别", "工具调用", "语音回答"),
                                "不执行设备控制或自动审批"));
    }

    @Test
    void expiresAReceiptUsingTheConfiguredVerificationTtl() {
        properties.setVerificationTtl(Duration.ofMinutes(1));
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, NOW.minus(Duration.ofMinutes(1)));

        ShowcaseScenario scenario = scenario(ShowcaseScenarioId.OPERATIONS_ANALYSIS);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.NOT_READY);
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署尚未完成在线验证");
    }

    @Test
    void disablesAlertWorkflowUnlessBothOnlineModesAreConfigured() {
        registry.recordSuccess(ShowcaseScenarioId.ALERT_WORKFLOW, NOW.minusSeconds(1));

        ShowcaseScenario mockKnowledge = catalog(
                "mock", "dashscope", true, true, collaborationProvider(true))
                .scenarios(NOW).get(0);
        ShowcaseScenario mockAnswer = catalog(
                "rag", "mock", true, true, collaborationProvider(true))
                .scenarios(NOW).get(0);

        assertThat(mockKnowledge.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
        assertThat(mockKnowledge.unavailableReason()).isEqualTo("本次部署未启用告警处置");
        assertThat(mockAnswer.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
        assertThat(mockAnswer.unavailableReason()).isEqualTo("本次部署未启用告警处置");
    }

    @Test
    void disablesCollaborationWhenItsRuntimeBeanIsAbsent() {
        registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, NOW.minusSeconds(1));
        catalog = catalog("rag", "dashscope", true, true, collaborationProvider(false));

        ShowcaseScenario scenario = scenario(ShowcaseScenarioId.EXPERT_COLLABORATION);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署未启用专家协作");
    }

    @Test
    void disablesAnalyticsWhenItsFeatureFlagIsOff() {
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, NOW.minusSeconds(1));
        catalog = catalog("rag", "dashscope", false, true, collaborationProvider(true));

        ShowcaseScenario scenario = scenario(ShowcaseScenarioId.OPERATIONS_ANALYSIS);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署未启用运营分析");
    }

    @Test
    void defaultsVerificationTtlToExactlyFifteenMinutes() {
        assertThat(new ShowcaseProperties().getVerificationTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rejectsAConfiguredVerificationTtlShorterThanOneMinute() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("smartpark.showcase.verification-ttl=59s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("smartpark.showcase.verification-ttl must be at least PT1M");
                });
    }

    @Test
    void wiresAConservativeDefaultCatalogAndQualifiedUtcClock() {
        new ApplicationContextRunner()
                .withUserConfiguration(ShowcaseConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ShowcaseProperties.class);
                    assertThat(context).hasSingleBean(ScenarioVerificationRegistry.class);
                    assertThat(context).hasSingleBean(ShowcaseScenarioCatalog.class);
                    assertThat(context.getBean("showcaseClock", Clock.class).getZone())
                            .isEqualTo(java.time.ZoneOffset.UTC);
                    assertThat(context.getBean(ShowcaseScenarioCatalog.class).scenarios(NOW))
                            .allSatisfy(scenario -> {
                                assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
                                assertThat(scenario.live()).isFalse();
                            });
                });
    }

    private ShowcaseScenario scenario(ShowcaseScenarioId id) {
        return catalog.scenarios(NOW).stream()
                .filter(item -> item.id() == id)
                .findFirst()
                .orElseThrow();
    }

    private ShowcaseScenarioCatalog catalog(
            String knowledgeMode,
            String customerAnswerMode,
            boolean analyticsEnabled,
            boolean voiceEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationProvider) {
        return new ShowcaseScenarioCatalog(registry, properties, knowledgeMode, customerAnswerMode,
                analyticsEnabled, voiceEnabled, collaborationProvider);
    }

    private static ObjectProvider<ExpertCollaborationService> collaborationProvider(boolean available) {
        ExpertCollaborationService service = available
                ? new ExpertCollaborationService(null, null, null, null, null, null, null, null)
                : null;
        return new ObjectProvider<>() {
            @Override public ExpertCollaborationService getIfAvailable() { return service; }
            @Override public ExpertCollaborationService getIfUnique() { return service; }
            @Override public ExpertCollaborationService getObject(Object... args) { return service; }
            @Override public ExpertCollaborationService getObject() { return service; }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShowcaseProperties.class)
    static class PropertiesConfiguration {
    }
}
