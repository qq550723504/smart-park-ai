package com.example.smartpark.showcase;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ShowcaseScenarioCatalog {

    private static final String NOT_READY_REASON = "本次部署尚未完成在线验证";

    private final ScenarioVerificationRegistry registry;
    private final ShowcaseProperties properties;
    private final boolean alertWorkflowEnabled;
    private final boolean analyticsEnabled;
    private final boolean voiceEnabled;
    private final ObjectProvider<ExpertCollaborationService> collaborationProvider;

    public ShowcaseScenarioCatalog(
            ScenarioVerificationRegistry registry,
            ShowcaseProperties properties,
            String knowledgeMode,
            String customerAnswerMode,
            boolean analyticsEnabled,
            boolean voiceEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationProvider) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.alertWorkflowEnabled = "rag".equals(knowledgeMode)
                && "dashscope".equals(customerAnswerMode);
        this.analyticsEnabled = analyticsEnabled;
        this.voiceEnabled = voiceEnabled;
        this.collaborationProvider = Objects.requireNonNull(
                collaborationProvider, "collaborationProvider");
    }

    public List<ShowcaseScenario> scenarios(Instant now) {
        Objects.requireNonNull(now, "now");
        return List.of(
                scenario(
                        ShowcaseScenarioId.ALERT_WORKFLOW,
                        alertWorkflowEnabled,
                        "本次部署未启用告警处置",
                        "告警处置",
                        "配电或暖通异常该如何处置？",
                        45,
                        List.of("在线知识检索", "在线模型回答"),
                        List.of("告警上下文", "处置知识", "风险闸门"),
                        "高风险处置必须由审批人确认",
                        now),
                scenario(
                        ShowcaseScenarioId.EXPERT_COLLABORATION,
                        collaborationProvider.getIfAvailable() != null,
                        "本次部署未启用专家协作",
                        "跨域专家协作",
                        "能耗、设备与安防是否存在关联？",
                        40,
                        List.of("专家协作运行时", "领域工具"),
                        List.of("专家分工", "工具证据", "汇总结论"),
                        "证据不足时保留人工复核",
                        now),
                scenario(
                        ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                        analyticsEnabled,
                        "本次部署未启用运营分析",
                        "运营分析",
                        "过去几天哪座楼能耗偏离基线？",
                        30,
                        List.of("模型", "只读数据"),
                        List.of("指标口径", "只读查询", "结果图表"),
                        "只读数据，不自动执行操作",
                        now),
                scenario(
                        ShowcaseScenarioId.VOICE_ASSISTANT,
                        voiceEnabled,
                        "本次部署未启用语音体验",
                        "实时语音助手",
                        "通过语音询问园区问题并获得在线回答",
                        30,
                        List.of("语音识别", "Agent 工具", "语音合成"),
                        List.of("语音识别", "工具调用", "语音回答"),
                        "不执行设备控制或自动审批",
                        now));
    }

    private ShowcaseScenario scenario(
            ShowcaseScenarioId id,
            boolean featureEnabled,
            String disabledReason,
            String title,
            String businessQuestion,
            int expectedDurationSeconds,
            List<String> requiredCapabilities,
            List<String> proofTypes,
            String humanBoundary,
            Instant now) {
        if (!featureEnabled) {
            return new ShowcaseScenario(id, ShowcaseScenarioStatus.DISABLED, false,
                    title, businessQuestion, expectedDurationSeconds, requiredCapabilities,
                    proofTypes, humanBoundary, disabledReason, null);
        }
        return registry.lastSuccessfulAt(id, now, properties.getVerificationTtl())
                .map(verifiedAt -> new ShowcaseScenario(id, ShowcaseScenarioStatus.READY, true,
                        title, businessQuestion, expectedDurationSeconds, requiredCapabilities,
                        proofTypes, humanBoundary, null, verifiedAt))
                .orElseGet(() -> new ShowcaseScenario(id, ShowcaseScenarioStatus.NOT_READY, false,
                        title, businessQuestion, expectedDurationSeconds, requiredCapabilities,
                        proofTypes, humanBoundary, NOT_READY_REASON, null));
    }
}
