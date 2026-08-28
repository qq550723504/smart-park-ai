package com.example.smartpark.voice.model;

/** Why an answer was rejected before TTS; always ends the turn explicitly. */
public enum AnswerRejectReason {
    EMPTY_ANSWER,
    UNSUPPORTED_CLAIM_NUMBER,
    UNSUPPORTED_CLAIM_IDENTIFIER,
    MISSING_POLICY_CITATION,
    UNKNOWN_POLICY_CITATION
}
