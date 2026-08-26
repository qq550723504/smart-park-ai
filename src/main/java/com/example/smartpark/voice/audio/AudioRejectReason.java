package com.example.smartpark.voice.audio;

/** Why an audio frame was rejected; safe to expose to clients and logs. */
public enum AudioRejectReason {
    NOT_ACCEPTING_AUDIO,
    EMPTY_PAYLOAD,
    MALFORMED_PCM,
    FRAME_TOO_LARGE,
    EXCESSIVE_FRAME_DURATION,
    TOTAL_DURATION_EXCEEDED
}
