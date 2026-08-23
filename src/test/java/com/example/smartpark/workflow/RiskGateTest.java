package com.example.smartpark.workflow;

import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.Alert;
import com.example.smartpark.model.AlertClassification;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.KnowledgeDocument;
import com.example.smartpark.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateTest {

    private final AlertWorkflowNodes.RiskGate riskGate = new AlertWorkflowNodes.RiskGate(0.75);

    @Test
    void lowRiskWithEnoughEvidenceRoutesToWorkOrder() {
        Route route = riskGate.route(
                alert(RiskLevel.LOW),
                classification(RiskLevel.LOW, 0.92),
                diagnosis(RiskLevel.LOW, 0.92),
                List.of(document()));

        assertThat(route).isEqualTo(Route.CREATE_WORK_ORDER);
    }

    @Test
    void highRiskRoutesToHumanApprovalEvenWhenTheModelDiagnosisIsLowRisk() {
        Route route = riskGate.route(
                alert(RiskLevel.HIGH),
                classification(RiskLevel.LOW, 0.95),
                diagnosis(RiskLevel.LOW, 0.95),
                List.of(document()));

        assertThat(route).isEqualTo(Route.WAIT_FOR_APPROVAL);
    }

    @Test
    void lowDiagnosisConfidenceRoutesToHumanApprovalEvenWhenClassificationConfidenceIsHigh() {
        Route route = riskGate.route(
                alert(RiskLevel.LOW),
                classification(RiskLevel.LOW, 0.99),
                diagnosis(RiskLevel.LOW, 0.74),
                List.of(document()));

        assertThat(route).isEqualTo(Route.WAIT_FOR_APPROVAL);
    }

    @Test
    void lowClassificationConfidenceRoutesToHumanApprovalEvenWhenDiagnosisConfidenceIsHigh() {
        Route route = riskGate.route(
                alert(RiskLevel.LOW),
                classification(RiskLevel.LOW, 0.74),
                diagnosis(RiskLevel.LOW, 0.99),
                List.of(document()));

        assertThat(route).isEqualTo(Route.WAIT_FOR_APPROVAL);
    }

    @Test
    void missingKnowledgeEvidenceRoutesToHumanApproval() {
        Route route = riskGate.route(
                alert(RiskLevel.LOW),
                classification(RiskLevel.LOW, 0.95),
                diagnosis(RiskLevel.LOW, 0.95),
                List.of());

        assertThat(route).isEqualTo(Route.WAIT_FOR_APPROVAL);
    }

    @Test
    void legacyDiagnosisWithoutConfidenceMigratesToFailClosedDefault() {
        Diagnosis legacyDiagnosis = new Diagnosis(
                "diag-legacy",
                "ALT-TEST-001",
                "DEV-TEST-001",
                RiskLevel.LOW,
                "legacy root cause",
                "legacy summary",
                List.of("legacy evidence"),
                "inspect device",
                Instant.parse("2026-08-23T01:30:00Z"));

        Route route = riskGate.route(
                alert(RiskLevel.LOW),
                classification(RiskLevel.LOW, 0.99),
                legacyDiagnosis,
                List.of(document()));

        assertThat(legacyDiagnosis.confidence()).isZero();
        assertThat(route).isEqualTo(Route.WAIT_FOR_APPROVAL);
    }

    private static Alert alert(RiskLevel riskLevel) {
        return new Alert(
                "ALT-TEST-001",
                "PARK-A",
                "A1",
                "DEV-TEST-001",
                AlertClassification.TEMPERATURE,
                riskLevel,
                "temperature alert",
                Instant.parse("2026-08-23T00:15:00Z"),
                List.of("sensor evidence"));
    }

    private static AlertTriageAgent.AlertClassificationResult classification(RiskLevel riskLevel, double confidence) {
        return new AlertTriageAgent.AlertClassificationResult(
                AlertClassification.TEMPERATURE,
                AlertTriageAgent.AlertPriority.MEDIUM,
                riskLevel,
                confidence);
    }

    private static Diagnosis diagnosis(RiskLevel riskLevel, double confidence) {
        return new Diagnosis(
                "diag-test",
                "ALT-TEST-001",
                "DEV-TEST-001",
                riskLevel,
                "restricted airflow",
                "filter inspection required",
                List.of("sensor evidence"),
                "inspect filter",
                confidence,
                Instant.parse("2026-08-23T01:30:00Z"));
    }

    private static KnowledgeDocument document() {
        return new KnowledgeDocument(
                "KD-TEST-001",
                "Temperature playbook",
                "Inspect airflow and filters.",
                List.of("temperature"),
                Instant.parse("2026-08-20T00:00:00Z"));
    }
}
