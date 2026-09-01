package com.example.smartpark.showcase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
                ShowcaseScenarioId.VOICE_ASSISTANT,
                ShowcaseScenarioId.CUSTOMER_SERVICE);
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

    @Test
    void acceptsANotReadyScenarioWithTheGenericUnverifiedReason() {
        ShowcaseScenario scenario = scenario(
                ShowcaseScenarioStatus.NOT_READY,
                false,
                "本次部署尚未完成在线验证",
                null);

        assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.NOT_READY);
        assertThat(scenario.live()).isFalse();
        assertThat(scenario.unavailableReason()).isEqualTo("本次部署尚未完成在线验证");
        assertThat(scenario.lastVerifiedAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("fixedDisabledReasons")
    void acceptsOnlyTheFixedDisabledReasonForEachScenario(
            ShowcaseScenarioId id, String unavailableReason) {
        ShowcaseScenario scenario = scenario(
                id, ShowcaseScenarioStatus.DISABLED, false, unavailableReason, null);

        assertThat(scenario.id()).isEqualTo(id);
        assertThat(scenario.unavailableReason()).isEqualTo(unavailableReason);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.lang.IllegalStateException: Connection timed out while invoking the online verification provider and collecting a long exception message for the customer response",
            "DashScope provider is unavailable",
            "请检查 http://smartpark-db.internal:5432/analytics",
            "请配置 DASHSCOPE_API_KEY",
            "api-key=sk-secret123",
            "SELECT * FROM energy_readings WHERE park_id = 'P-001'",
            "系统提示词：你是园区运营分析助手，请输出内部推理过程",
            "园区 P-001 的设备 DEV-007 温度为 92°C，负责人手机号 13800138000"
    })
    void rejectsSensitiveOrInternalReasonsOutsideTheClosedContract(String unavailableReason) {
        assertThatThrownBy(() -> scenario(
                ShowcaseScenarioStatus.NOT_READY, false, unavailableReason, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOtherChineseReasonsOutsideTheClosedContract() {
        assertThatThrownBy(() -> scenario(
                ShowcaseScenarioStatus.NOT_READY, false, "本次部署暂时不可用", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADisabledReasonForTheWrongScenario() {
        assertThatThrownBy(() -> scenario(
                ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                ShowcaseScenarioStatus.DISABLED,
                false,
                "本次部署未启用语音体验",
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ShowcaseScenarioStatus.class, names = {"NOT_READY", "DISABLED"})
    void rejectsANonReadyScenarioThatIsLive(ShowcaseScenarioStatus status) {
        assertThatThrownBy(() -> scenario(status, true, validUnavailableReason(status), null))
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
                status, false, validUnavailableReason(status), VERIFIED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> fixedDisabledReasons() {
        return Stream.of(
                Arguments.of(ShowcaseScenarioId.ALERT_WORKFLOW, "本次部署未启用告警处置"),
                Arguments.of(ShowcaseScenarioId.EXPERT_COLLABORATION, "本次部署未启用专家协作"),
                Arguments.of(ShowcaseScenarioId.OPERATIONS_ANALYSIS, "本次部署未启用运营分析"),
                Arguments.of(ShowcaseScenarioId.VOICE_ASSISTANT, "本次部署未启用语音体验"),
                Arguments.of(ShowcaseScenarioId.CUSTOMER_SERVICE, "本次部署未启用园区客服"));
    }

    private String validUnavailableReason(ShowcaseScenarioStatus status) {
        return status == ShowcaseScenarioStatus.NOT_READY
                ? "本次部署尚未完成在线验证"
                : "本次部署未启用专家协作";
    }

    private ShowcaseScenario scenario(ShowcaseScenarioStatus status, boolean live,
                                      String unavailableReason, Instant lastVerifiedAt) {
        return scenario(ShowcaseScenarioId.EXPERT_COLLABORATION,
                status, live, unavailableReason, lastVerifiedAt);
    }

    private ShowcaseScenario scenario(ShowcaseScenarioId id, ShowcaseScenarioStatus status,
                                      boolean live, String unavailableReason,
                                      Instant lastVerifiedAt) {
        return new ShowcaseScenario(
                id,
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
