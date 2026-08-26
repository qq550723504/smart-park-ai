package com.example.smartpark.voice.model;

/** Session lifecycle states; ERROR is retryable, CLOSED is terminal and unrecoverable. */
public enum VoiceSessionState {
    IDLE,
    LISTENING,
    ASR_FINALIZED,
    REASONING,
    TOOL_CALLING,
    ANSWER_STREAMING,
    SPEAKING,
    ERROR,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
