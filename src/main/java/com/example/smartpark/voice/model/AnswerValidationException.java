package com.example.smartpark.voice.model;

/**
 * Raised when the streamed answer fails evidence validation. There is no
 * fallback answer: the turn ends explicitly with this failure surfaced to users.
 */
public final class AnswerValidationException extends RuntimeException {

    private final AnswerRejectReason reason;

    public AnswerValidationException(AnswerRejectReason reason) {
        super("voice answer rejected: " + reason);
        this.reason = reason;
    }

    public AnswerRejectReason reason() {
        return reason;
    }
}
