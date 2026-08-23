package com.example.smartpark.workflow;

import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessmentTest {

    @Test
    void explainsEveryReasonThatRequiresApproval() {
        Alert alert = new Alert("ALT-1", "PARK-A", "A1", "DEV-1",
                com.example.smartpark.model.alert.AlertClassification.TEMPERATURE,
                RiskLevel.HIGH, "alert", Instant.parse("2026-08-23T00:00:00Z"), List.of());
        AlertTriageAgent.AlertClassificationResult classification = new AlertTriageAgent.AlertClassificationResult(
                com.example.smartpark.model.alert.AlertClassification.TEMPERATURE,
                com.example.smartpark.agent.AlertTriageAgent.AlertPriority.HIGH, RiskLevel.HIGH, 0.4);
        Diagnosis diagnosis = new Diagnosis("diag-1", "ALT-1", "DEV-1", RiskLevel.HIGH,
                "cause", "summary", List.of("evidence"), "action", 0.5,
                Instant.parse("2026-08-23T00:00:00Z"));

        RiskAssessment assessment = new AlertWorkflowNodes.RiskGate(0.75)
                .assess(alert, classification, diagnosis, List.<KnowledgeDocument>of());

        assertThat(assessment.route()).isEqualTo(Route.WAIT_FOR_APPROVAL);
        assertThat(assessment.reasons()).containsExactlyInAnyOrder(
                "原始告警风险为 HIGH",
                "分诊风险为 HIGH",
                "诊断风险为 HIGH",
                "分诊置信度低于 0.75",
                "诊断置信度低于 0.75",
                "没有检索到知识证据");
    }
}
