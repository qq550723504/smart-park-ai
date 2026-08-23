package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.ParkContext;
import com.example.smartpark.model.common.WorkOrder;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class PromptCatalog {

    private PromptCatalog() {
    }

    static String triageSystemPrompt() {
        return """
                你是智慧园区告警分诊智能体。
                只能返回 JSON，并且必须严格包含以下字段：
                {
                  "category": "AlertClassification 枚举值之一",
                  "priority": "LOW | MEDIUM | HIGH",
                  "riskLevel": "RiskLevel 枚举值之一",
                  "confidence": "0 到 1 之间的数字"
                }
                只能使用给定的告警信息。
                不得猜测缺失事实。
                证据不足时，应选择最保守的有效分类并降低置信度，不得编造数据。
                """;
    }

    static String triageUserPrompt(Alert alert) {
        Objects.requireNonNull(alert, "alert");
        return """
                待分诊告警：
                - id: %s
                - parkId: %s
                - buildingId: %s
                - deviceId: %s
                - currentCategory: %s
                - riskHint: %s
                - summary: %s
                - occurredAt: %s
                - evidence: %s
                """.formatted(
                alert.id(),
                alert.parkId(),
                alert.buildingId(),
                alert.deviceId(),
                alert.classification(),
                alert.riskHint(),
                alert.summary(),
                alert.occurredAt(),
                alert.evidence());
    }

    static String diagnosisSystemPrompt(List<String> availableToolNames) {
        // 工具名称属于程序契约，因此保留英文；面向模型的说明使用中文。
        List<String> toolNames = List.copyOf(Objects.requireNonNull(availableToolNames, "availableToolNames"));
        return """
                你是智慧园区告警诊断智能体。
                只能返回 JSON，并且必须严格包含以下字段：
                {
                  "id": "非空诊断编号",
                  "alertId": "非空告警编号",
                  "deviceId": "非空设备编号",
                  "riskLevel": "RiskLevel 枚举值之一",
                  "rootCause": "非空根因假设",
                  "summary": "非空诊断摘要",
                  "evidence": ["一条或多条证据"],
                  "recommendedAction": "非空处置建议",
                  "confidence": "0 到 1 之间的诊断置信度",
                  "diagnosedAt": "ISO-8601 时间"
                }
                每项结论都必须有证据支持。
                工具数据或知识缺失表示证据不足，不代表可以猜测。
                可用的只读工具：%s
                此步骤禁止创建或修改工单。
                """.formatted(toolNames);
    }

    static String diagnosisUserPrompt(Alert alert, ParkContext context, List<KnowledgeDocument> documents) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(context, "context");
        List<KnowledgeDocument> safeDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        return """
                待诊断告警：
                %s

                园区上下文：
                - parkId: %s
                - buildingId: %s
                - device: %s
                - alertHistory:
                %s
                - workOrders:
                %s

                知识文档：
                %s
                """.formatted(
                triageUserPrompt(alert),
                context.parkId(),
                context.buildingId(),
                renderDevice(context),
                renderAlertHistory(context),
                renderWorkOrders(context.workOrders()),
                renderKnowledgeDocuments(safeDocuments));
    }

    private static String renderDevice(ParkContext context) {
        return """
                {
                  "id": "%s",
                  "name": "%s",
                  "category": "%s",
                  "status": "%s",
                  "installedAt": "%s"
                }
                """.formatted(
                context.device().id(),
                context.device().name(),
                context.device().category(),
                context.device().status(),
                context.device().installedAt());
    }

    private static String renderAlertHistory(ParkContext context) {
        if (context.alertHistory().isEmpty()) {
            return "  - 无";
        }
        return context.alertHistory().stream()
                .map(alert -> "  - %s | %s | %s".formatted(alert.id(), alert.occurredAt(), alert.summary()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String renderWorkOrders(List<WorkOrder> workOrders) {
        if (workOrders.isEmpty()) {
            return "  - 无";
        }
        return workOrders.stream()
                .map(workOrder -> "  - %s | %s | %s | approval=%s".formatted(
                        workOrder.id(),
                        workOrder.workflowId(),
                        workOrder.status(),
                        workOrder.approvalDecision()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String renderKnowledgeDocuments(List<KnowledgeDocument> documents) {
        if (documents.isEmpty()) {
            return "INSUFFICIENT_EVIDENCE：没有匹配本次请求的知识文档";
        }
        return documents.stream()
                .map(document -> """
                        - id: %s
                          title: %s
                          tags: %s
                          updatedAt: %s
                          content: %s
                        """.formatted(
                        document.id(),
                        document.title(),
                        document.tags(),
                        document.updatedAt(),
                        document.content()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
