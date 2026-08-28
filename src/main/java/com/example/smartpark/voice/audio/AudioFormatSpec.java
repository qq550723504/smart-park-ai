package com.example.smartpark.voice.audio;

/** The single PCM input format accepted from clients; everything else is rejected. */
public final class AudioFormatSpec {

    public static final int SAMPLE_RATE_HZ = 16000;
    public static final int CHANNELS = 1;
    public static final int SAMPLE_SIZE_BITS = 16;

    /** 16000 samples/s * 2 bytes = 32 bytes per millisecond of mono int16 PCM. */
    private static final int BYTES_PER_MS =
            SAMPLE_RATE_HZ * (SAMPLE_SIZE_BITS / 8) / 1000 * CHANNELS;

    public int sampleRate() {
        return SAMPLE_RATE_HZ;
    }

    public int channels() {
        return CHANNELS;
    }

    public int sampleSizeBits() {
        return SAMPLE_SIZE_BITS;
    }

    public int bytesPerMillisecond() {
        return BYTES_PER_MS;
    }

    public int bytesPerSecond() {
        return SAMPLE_RATE_HZ * (SAMPLE_SIZE_BITS / 8) * CHANNELS;
    }
}
