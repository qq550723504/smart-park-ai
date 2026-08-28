package com.example.smartpark.voice.model;

/**
 * Internal events driving the session state machine. Client controls map onto
 * {@link VoiceClientControlType}; the remaining events are produced by the
 * session service as ASR/agent/TTS progress.
 */
public enum VoiceSessionEvent {
    START_INPUT,
    INPUT_COMMITTED,
    REASONING_STARTED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    ANSWER_STREAM_STARTED,
    ANSWER_COMPLETED,
    TURN_COMPLETED,
    OUTPUT_INTERRUPTED,
    ERROR_OCCURRED,
    SESSION_CLOSED
}
