package com.example.smartpark.voice.model;

/** Identity contract implemented by all voice JSON frames. */
public interface VoiceFrame {

    VoiceEnvelope envelope();

    String sessionId();

    String messageId();

    long sequence();
}
