package com.example.smartpark.voice.audio;

import java.time.Duration;
import java.util.Arrays;

/**
 * In-memory ring buffer holding at most the configured input budget of PCM for
 * the current turn. Nothing here ever touches disk or event payloads; release()
 * zeroes the backing array so completed turns leave no audio references.
 */
public final class VoiceAudioRingBuffer {

    private final byte[] backing;
    private final long capacityBytes;
    private long sizeBytes;

    public VoiceAudioRingBuffer(AudioFormatSpec spec, Duration maxInputDuration) {
        this.capacityBytes = (long) spec.bytesPerMillisecond() * maxInputDuration.toMillis();
        this.backing = new byte[(int) capacityBytes];
    }

    /** Appends an owned chunk copy into the ring buffer. */
    public void append(AudioChunk chunk) {
        if (chunk.lengthBytes() > remainingCapacity()) {
            throw new IllegalArgumentException(
                    "audio exceeds in-memory budget: " + remainingCapacity() + " bytes free");
        }
        byte[] data = chunk.data();
        long head = sizeBytes % capacityBytes;
        int firstPart = (int) Math.min(data.length, capacityBytes - head);
        System.arraycopy(data, 0, backing, (int) head, firstPart);
        if (data.length > firstPart) {
            System.arraycopy(data, firstPart, backing, 0, data.length - firstPart);
        }
        sizeBytes += data.length;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public long remainingCapacity() {
        return capacityBytes - Math.min(sizeBytes, capacityBytes);
    }

    /**
     * Takes full ownership of the buffered PCM (oldest to newest). Callers pass
     * this snapshot to ASR; the buffer itself drops its reference immediately.
     */
    public byte[] snapshot() {
        if (sizeBytes == 0) {
            return new byte[0];
        }
        long head = sizeBytes % capacityBytes;
        byte[] out = new byte[(int) Math.min(sizeBytes, capacityBytes)];
        if (head == 0) {
            System.arraycopy(backing, 0, out, 0, out.length);
        } else if (sizeBytes <= capacityBytes) {
            System.arraycopy(backing, 0, out, 0, out.length);
        } else {
            int tailLength = (int) head;
            System.arraycopy(backing, tailLength, out, 0, out.length - tailLength);
            System.arraycopy(backing, 0, out, out.length - tailLength, tailLength);
        }
        return out;
    }

    /**
     * Releases all audio references: zeroes the backing array so no PCM bytes
     * survive turn completion even if heap dumps are inspected later.
     */
    public void release() {
        Arrays.fill(backing, (byte) 0);
        sizeBytes = 0;
    }

    /** Test-only leak check accessor; production code must not call this. */
    byte[] backingArrayForLeakCheckOnly() {
        return backing;
    }
}
