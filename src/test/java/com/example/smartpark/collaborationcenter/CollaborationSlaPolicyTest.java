package com.example.smartpark.collaborationcenter;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationSlaPolicyTest {

    private final CollaborationSlaPolicy policy = new CollaborationSlaPolicy();
    private final Instant now = Instant.parse("2026-09-02T10:00:00Z");

    @Test
    void marksHighPriorityAlertDueSoonNearItsDeadline() {
        CollaborationSlaPolicy.SlaEvaluation evaluation = policy.evaluate(
                CollaborationWorkItem.Source.ALERT_WORKFLOW,
                CollaborationWorkItem.Priority.HIGH,
                CollaborationWorkItem.Status.WAITING_APPROVAL,
                Instant.parse("2026-09-02T09:35:00Z"), now);

        assertThat(evaluation.state()).isEqualTo(CollaborationWorkItem.SlaState.DUE_SOON);
        assertThat(evaluation.dueAt()).isEqualTo(Instant.parse("2026-09-02T10:05:00Z"));
    }

    @Test
    void marksCustomerTicketOverdueAfterItsFourHourWindow() {
        CollaborationSlaPolicy.SlaEvaluation evaluation = policy.evaluate(
                CollaborationWorkItem.Source.CUSTOMER_TICKET,
                CollaborationWorkItem.Priority.NORMAL,
                CollaborationWorkItem.Status.WAITING_AGENT,
                Instant.parse("2026-09-02T05:00:00Z"), now);

        assertThat(evaluation.state()).isEqualTo(CollaborationWorkItem.SlaState.OVERDUE);
        assertThat(evaluation.dueAt()).isEqualTo(Instant.parse("2026-09-02T09:00:00Z"));
    }

    @Test
    void marksTerminalItemsCompleted() {
        CollaborationSlaPolicy.SlaEvaluation evaluation = policy.evaluate(
                CollaborationWorkItem.Source.ALERT_WORKFLOW,
                CollaborationWorkItem.Priority.HIGH,
                CollaborationWorkItem.Status.COMPLETED,
                Instant.parse("2026-09-02T08:00:00Z"), now);

        assertThat(evaluation.state()).isEqualTo(CollaborationWorkItem.SlaState.COMPLETED);
    }
}
