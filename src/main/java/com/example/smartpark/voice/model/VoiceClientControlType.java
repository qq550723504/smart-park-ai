package com.example.smartpark.voice.model;

/** Controls sent by the client over the session WebSocket as JSON frames. */
public enum VoiceClientControlType {
    START_INPUT,
    COMMIT_INPUT,
    INTERRUPT_OUTPUT,
    CLOSE_SESSION
}
