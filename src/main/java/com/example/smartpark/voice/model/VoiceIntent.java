package com.example.smartpark.voice.model;

/**
 * Voice intents the session can route to. WRITE_REQUEST never reaches any tool:
 * the assistant answers with an explicit read-only refusal instead.
 */
public enum VoiceIntent {
    ALERT,
    ENERGY,
    PARKING_POLICY,
    CHITCHAT,
    WRITE_REQUEST
}
