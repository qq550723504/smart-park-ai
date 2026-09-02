package com.example.smartpark.collaborationcenter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class CollaborationSlaPolicy {
    private static final Duration HIGH_ALERT_WINDOW = Duration.ofMinutes(30);
    private static final Duration NORMAL_ALERT_WINDOW = Duration.ofHours(2);
    private static final Duration CUSTOMER_TICKET_WINDOW = Duration.ofHours(4);

    public SlaEvaluation evaluate(CollaborationWorkItem.Source source, CollaborationWorkItem.Priority priority,
                                  CollaborationWorkItem.Status status, Instant openedAt, Instant now) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        if (isTerminal(status)) return new SlaEvaluation(CollaborationWorkItem.SlaState.COMPLETED, null);
        if (openedAt == null) return new SlaEvaluation(CollaborationWorkItem.SlaState.NOT_APPLICABLE, null);
        Duration window = source == CollaborationWorkItem.Source.CUSTOMER_TICKET
                ? CUSTOMER_TICKET_WINDOW
                : priority == CollaborationWorkItem.Priority.HIGH ? HIGH_ALERT_WINDOW : NORMAL_ALERT_WINDOW;
        Instant dueAt = openedAt.plus(window);
        Duration remaining = Duration.between(now, dueAt);
        CollaborationWorkItem.SlaState state = remaining.isNegative() || remaining.isZero()
                ? CollaborationWorkItem.SlaState.OVERDUE
                : remaining.compareTo(window.dividedBy(5)) <= 0
                    ? CollaborationWorkItem.SlaState.DUE_SOON
                    : CollaborationWorkItem.SlaState.ON_TRACK;
        return new SlaEvaluation(state, dueAt);
    }

    private static boolean isTerminal(CollaborationWorkItem.Status status) {
        return switch (status) {
            case COMPLETED, REJECTED, RESOLVED, CLOSED, CANCELLED -> true;
            default -> false;
        };
    }

    public record SlaEvaluation(CollaborationWorkItem.SlaState state, Instant dueAt) {
        public SlaEvaluation { Objects.requireNonNull(state, "state"); }
    }
}
