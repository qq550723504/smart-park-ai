package com.example.smartpark.voice.model;

/** Server frame types published on the session WebSocket and unified event streams. */
public enum VoiceServerFrameType {
    SESSION_STATE,
    ASR_PARTIAL,
    ASR_FINAL,
    TOOL_EVENT,
    ANSWER_DELTA,
    AUDIO_CHUNK,
    ERROR
}
