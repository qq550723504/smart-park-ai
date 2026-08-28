package com.example.smartpark.voice.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Base contract for all server-published frames. */
public interface VoiceServerFrame extends VoiceFrame {

    @JsonProperty("type")
    VoiceServerFrameType type();
}
