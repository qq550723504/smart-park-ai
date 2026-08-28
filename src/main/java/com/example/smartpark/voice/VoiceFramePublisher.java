package com.example.smartpark.voice;

import com.example.smartpark.voice.model.VoiceServerFrame;

/**
 * Per-connection outbound sink. Implementations bridge typed frames onto the
 * WebSocket connection; raw PCM never travels inside JSON frames.
 */
public interface VoiceFramePublisher {

    void publish(VoiceServerFrame frame);

    /** Sends raw PCM with its chunk sequence; format is transport-level only. */
    void publishAudioChunk(int chunkSequence, byte[] pcm);

    default void close() {
    }
}
