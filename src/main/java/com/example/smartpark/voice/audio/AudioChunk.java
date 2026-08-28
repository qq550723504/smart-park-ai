package com.example.smartpark.voice.audio;

/**
 * Validated PCM chunk with copy-on-transfer semantics so callers can never
 * mutate a buffer still owned by the session pipeline.
 */
public final class AudioChunk {

    private final byte[] data;
    private final long durationMs;

    public AudioChunk(byte[] pcm, long durationMs) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("pcm payload must not be empty");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.data = pcm.clone();
        this.durationMs = durationMs;
    }

    /** Defensive copy; ownership passes to the receiver without aliasing. */
    public byte[] data() {
        return data.clone();
    }

    public int lengthBytes() {
        return data.length;
    }

    public long durationMs() {
        return durationMs;
    }
}
