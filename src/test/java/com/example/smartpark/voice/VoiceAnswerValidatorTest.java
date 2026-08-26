package com.example.smartpark.voice;

import com.example.smartpark.voice.model.AnswerRejectReason;
import com.example.smartpark.voice.model.ToolCallRecord;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceAnswerValidatorTest {

    private final VoiceAnswerValidator validator = new VoiceAnswerValidator();

    private static final ToolCallRecord ENERGY_TOOL = new ToolCallRecord(
            "lookupEnergyConsumption",
            "meterId=DEV-ENERGY-001",
            "meterId=DEV-ENERGY-001 currentKwh=138.0 baselineKwh=100.0 peakDemandKw=42.5");

    @Test
    void rejectsBlankAnswer() {
        assertThatThrownBy(() -> validator.validate(
                VoiceIntent.ENERGY, new VoiceAnswer("  ", List.of(), List.of(ENERGY_TOOL))))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.EMPTY_ANSWER);
    }

    @Test
    void acceptsNumbersTraceableToThisTurnEvidence() {
        assertThatCode(() -> validator.validate(VoiceIntent.ENERGY, new VoiceAnswer(
                "A2 表计当前用电 138 千瓦时，高于基线 100 千瓦时。",
                List.of("DEV-ENERGY-001"),
                List.of(ENERGY_TOOL))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNumbersWithoutMatchingEvidence() {
        assertThatThrownBy(() -> validator.validate(VoiceIntent.ENERGY, new VoiceAnswer(
                "A2 表计当前用电 999 千瓦时。",
                List.of("DEV-ENERGY-001"),
                List.of(ENERGY_TOOL))))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.UNSUPPORTED_CLAIM_NUMBER);
    }

    @Test
    void rejectsIdentifiersNotPresentInEvidence() {
        assertThatThrownBy(() -> validator.validate(VoiceIntent.ALERT, new VoiceAnswer(
                "告警 ALT-TEMP-999 已确认：DEV-HVAC-001 温度上升。",
                List.of("ALT-TEMP-001", "DEV-HVAC-001"),
                List.of(new ToolCallRecord("lookupAlert", "alertId=ALT-TEMP-001",
                        "alertId=ALT-TEMP-001 deviceId=DEV-HVAC-001 riskLevel=LOW")))))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.UNSUPPORTED_CLAIM_IDENTIFIER);
    }

    @Test
    void turnsWithoutToolCallsMayNotContainAnyNumbers() {
        // 未调用工具却给出数据 —— 显式拒绝。
        assertThatThrownBy(() -> validator.validate(VoiceIntent.CHITCHAT, new VoiceAnswer(
                "园区今天用电 138 千瓦时。", List.of(), List.of())))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.UNSUPPORTED_CLAIM_NUMBER);

        assertThatCode(() -> validator.validate(VoiceIntent.CHITCHAT, new VoiceAnswer(
                "你好，我是园区语音助手，可以帮你查询告警和用能情况。", List.of(), List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void parkingPolicyAnswersMustCiteKnownDocumentsOnly() {
        VoiceAnswer cited = new VoiceAnswer(
                "访客车辆请先完成入场登记后再使用访客停车场。[doc:KD-PARKING-001]",
                List.of("KD-PARKING-001"),
                List.of(new ToolCallRecord("searchVisitorGuide", "query=停车",
                        "documentId=KD-PARKING-001 title=Visitor parking guide")));
        assertThatCode(() -> validator.validate(VoiceIntent.PARKING_POLICY, cited))
                .doesNotThrowAnyException();

        // 引用了本 turn 知识结果之外的政策 —— 拒绝。
        assertThatThrownBy(() -> validator.validate(VoiceIntent.PARKING_POLICY, new VoiceAnswer(
                "访客停车免费。[doc:KD-FAKE-999]", List.of("KD-PARKING-001"), cited.toolCalls())))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.UNKNOWN_POLICY_CITATION);

        // 政策类回答缺少任何引用 —— 拒绝。
        assertThatThrownBy(() -> validator.validate(VoiceIntent.PARKING_POLICY, new VoiceAnswer(
                "访客车辆请先完成入场登记。", List.of("KD-PARKING-001"), cited.toolCalls())))
                .hasFieldOrPropertyWithValue("reason", AnswerRejectReason.MISSING_POLICY_CITATION);
    }
}
