package com.example.smartpark.showcase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcaseScenarioTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-08-30T10:00:00Z");

    @Test
    void keepsThePublicCatalogEnumsClosed() {
        assertThat(ShowcaseScenarioId.values()).containsExactly(
                ShowcaseScenarioId.ALERT_WORKFLOW,
                ShowcaseScenarioId.EXPERT_COLLABORATION,
                ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                ShowcaseScenarioId.VOICE_ASSISTANT);
        assertThat(ShowcaseScenarioStatus.values()).containsExactly(
                ShowcaseScenarioStatus.READY,
                ShowcaseScenarioStatus.NOT_READY,
                ShowcaseScenarioStatus.DISABLED);
    }

    @Test
    void acceptsAReadyScenarioAndKeepsItsCollectionsImmutable() {
        List<String> requiredCapabilities = new ArrayList<>(List.of("模型", "只读数据"));
        List<String> proofTypes = new ArrayList<>(List.of("指标口径", "只读查询"));

        ShowcaseScenario scenario = new ShowcaseScenario(
                ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                ShowcaseScenarioStatus.READY,
                true,
                "运营分析 Agent",
                "本周园区能耗异常在哪里？",
                30,
                requiredCapabilities,
                proofTypes,
                "只读数据，不自动执行操作",
                null,
                VERIFIED_AT);

        requiredCapabilities.add("后来添加的能力");
        proofTypes.clear();

        assertThat(scenario.id()).isEqualTo(ShowcaseScenarioId.OPERATIONS_ANALYSIS);
        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.READY);
        assertThat(scenario.live()).isTrue();
        assertThat(scenario.title()).isEqualTo("运营分析 Agent");
        assertThat(scenario.businessQuestion()).isEqualTo("本周园区能耗异常在哪里？");
        assertThat(scenario.expectedDurationSeconds()).isEqualTo(30);
        assertThat(scenario.requiredCapabilities()).containsExactly("模型", "只读数据");
        assertThat(scenario.proofTypes()).containsExactly("指标口径", "只读查询");
        assertThat(scenario.humanBoundary()).isEqualTo("只读数据，不自动执行操作");
        assertThat(scenario.unavailableReason()).isNull();
        assertThat(scenario.lastVerifiedAt()).isEqualTo(VERIFIED_AT);
        assertThatThrownBy(() -> scenario.requiredCapabilities().add("不可修改"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAReadyScenarioThatIsNotLive() {
        assertThatThrownBy(() -> scenario(ShowcaseScenarioStatus.READY, false, null, VERIFIED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAReadyScenarioWithAnUnavailableReason() {
        assertThatThrownBy(() -> scenario(
                ShowcaseScenarioStatus.READY, true, "仍不可用", VERIFIED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAReadyScenarioWithoutAVerificationReceipt() {
        assertThatThrownBy(() -> scenario(ShowcaseScenarioStatus.READY, true, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ShowcaseScenarioStatus.class, names = {"NOT_READY", "DISABLED"})
    void acceptsANonReadyScenarioWithASafeReason(ShowcaseScenarioStatus status) {
        ShowcaseScenario scenario = scenario(status, false, "本次部署尚未完成在线验证", null);

        assertThat(scenario.status()).isEqualTo(status);
        assertThat(scenario.live()).isFalse();
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署尚未完成在线验证");
        assertThat(scenario.lastVerifiedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ShowcaseScenarioStatus.class, names = {"NOT_READY", "DISABLED"})
    void rejectsANonReadyScenarioThatIsLive(ShowcaseScenarioStatus status) {
        assertThatThrownBy(() -> scenario(status, true, "本次部署尚未完成在线验证", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ShowcaseScenarioStatus.class, names = {"NOT_READY", "DISABLED"})
    void rejectsANonReadyScenarioWithoutASafeReason(ShowcaseScenarioStatus status) {
        assertThatThrownBy(() -> scenario(status, false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scenario(status, false, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scenario(status, false, "不可用\r\n内部错误", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ShowcaseScenarioStatus.class, names = {"NOT_READY", "DISABLED"})
    void rejectsANonReadyScenarioWithAVerificationReceipt(ShowcaseScenarioStatus status) {
        assertThatThrownBy(() -> scenario(
                status, false, "本次部署尚未完成在线验证", VERIFIED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ShowcaseScenario scenario(ShowcaseScenarioStatus status, boolean live,
                                      String unavailableReason, Instant lastVerifiedAt) {
        return new ShowcaseScenario(
                ShowcaseScenarioId.EXPERT_COLLABORATION,
                status,
                live,
                "多专家协同 Agent",
                "跨域异常的根因是什么？",
                45,
                List.of("模型", "工具"),
                List.of("专家发现", "综合结论"),
                "建议需由人工确认",
                unavailableReason,
                lastVerifiedAt);
    }
}
