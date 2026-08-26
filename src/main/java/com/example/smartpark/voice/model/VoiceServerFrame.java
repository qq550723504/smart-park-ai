package com.example.smartpark.voice.model;

/** Base contract for all server-published frames. */
public interface VoiceServerFrame extends VoiceFrame {

    VoiceServerFrameType type();
}
