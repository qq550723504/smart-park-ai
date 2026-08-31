package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class PromptCatalog {

    private PromptCatalog() {
    }

    static String triageSystemPrompt() {
        return """
                You are the smart-park alert triage agent.
                Classify with category (%s), priority (LOW | MEDIUM | HIGH), riskLevel (%s),
                and a numeric confidence from 0 to 1.
                Use the supplied alert only.
                Do not guess missing facts.
                If the evidence is insufficient, choose the most conservative valid classification and lower confidence instead of inventing data.
                """.formatted(enumValues(AlertClassification.class), enumValues(RiskLevel.class));
    }

    static String triageUserPrompt(Alert alert) {
        Objects.requireNonNull(alert, "alert");
        return """
                Alert to triage:
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
        // 工具名称属于程序契约，因此保留英文；面向模型的说明由提示词统一管理。
        List<String> toolNames = List.copyOf(Objects.requireNonNull(availableToolNames, "availableToolNames"));
        return """
                You are the smart-park diagnosis agent.
                Diagnose with riskLevel (%s), a non-empty rootCause, summary, one or more evidence
                statements, a non-empty recommendedAction, and a numeric confidence from 0 to 1.
                Diagnosis identity, alert identity, device identity, and diagnosis time are server-owned;
                do not invent or return them.
                Every conclusion must be backed by evidence.
                Missing tool data or missing knowledge is evidence insufficiency, not permission to guess.
                Available read-only tools: %s
                You are not allowed to create or mutate work orders in this step.
                """.formatted(enumValues(RiskLevel.class), toolNames);
    }

    static String diagnosisUserPrompt(Alert alert, ParkContext context, List<KnowledgeDocument> documents) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(context, "context");
        List<KnowledgeDocument> safeDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        return """
                Alert under diagnosis:
                %s

                Park context:
                - parkId: %s
                - buildingId: %s
                - device: %s
                - alertHistory:
                %s
                - workOrders:
                %s

                Knowledge documents:
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

    static String strictRetryInstruction() {
        return """

                The previous response did not satisfy the output contract. Retry once.
                Return exactly one JSON object with the required fields and valid enum values.
                Do not include Markdown fences, explanations, comments, or extra fields.
                """;
    }

    private static <E extends Enum<E>> String enumValues(Class<E> enumType) {
        return java.util.Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(" | "));
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
            return "  - none";
        }
        return context.alertHistory().stream()
                .map(alert -> "  - %s | %s | %s".formatted(alert.id(), alert.occurredAt(), alert.summary()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String renderWorkOrders(List<WorkOrder> workOrders) {
        if (workOrders.isEmpty()) {
            return "  - none";
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
            return "INSUFFICIENT_EVIDENCE: no knowledge documents matched the request";
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
