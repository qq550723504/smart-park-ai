package com.example.smartpark.voice.model;

/**
 * Safe, display-ready error codes. Raw provider exceptions and payloads never
 * cross the protocol boundary; they are mapped onto these codes server-side.
 */
public enum VoiceErrorCode {
    INVALID_FRAME,
    UNSUPPORTED_STATE,
    AUDIO_REJECTED,
    PROVIDER_FAILURE,
    TIMEOUT,
    ANSWER_VALIDATION_FAILED,
    INTERNAL_ERROR
}
