package com.example.smartpark.voice.audio;

import com.example.smartpark.voice.model.VoiceSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Validates inbound PCM frames against the single accepted audio format and the
 * session memory budget. Rejected frames never enter any buffer, and log output
 * is limited to rejection reasons so raw audio can never leak into logs.
 */
public final class AudioFrameValidator {

    private static final Logger log = LoggerFactory.getLogger(AudioFrameValidator.class);

    private final AudioFormatSpec spec = new AudioFormatSpec();
    private final long maxTotalBytes;
    private final int maxFrameBytes;
    private final long maxFrameDurationMs;
    private long acceptedTotalBytes;

    public AudioFrameValidator(AudioFormatSpec spec,
                               Duration maxInputDuration,
                               int maxFrameBytes,
                               long maxFrameDurationMs) {
        this.maxTotalBytes = spec.bytesPerMillisecond() * maxInputDuration.toMillis();
        this.maxFrameBytes = maxFrameBytes;
        this.maxFrameDurationMs = maxFrameDurationMs;
    }

    /**
     * Validates one frame and returns an owned chunk. Only successful validations
     * count toward the cumulative 10-second input budget.
     *
     * @throws AudioRejectionException with a reason-safe message on any violation
     */
    public AudioChunk validate(byte[] pcm, VoiceSessionState state) {
        if (state != VoiceSessionState.LISTENING) {
            throw reject(AudioRejectReason.NOT_ACCEPTING_AUDIO);
        }
        if (pcm == null || pcm.length == 0) {
            throw reject(AudioRejectReason.EMPTY_PAYLOAD);
        }
        if (pcm.length % 2 != 0) {
            // 16-bit samples must arrive as whole sample pairs.
            throw reject(AudioRejectReason.MALFORMED_PCM);
        }
        long impliedDurationMs = pcm.length / spec.bytesPerMillisecond();
        if (impliedDurationMs > maxFrameDurationMs
                || (pcm.length % spec.bytesPerMillisecond() > 0 && pcm.length / spec.bytesPerMillisecond() == maxFrameDurationMs)) {
            throw reject(AudioRejectReason.EXCESSIVE_FRAME_DURATION);
        }
        if (pcm.length > maxFrameBytes) {
            throw reject(AudioRejectReason.FRAME_TOO_LARGE);
        }
        if (acceptedTotalBytes + pcm.length > maxTotalBytes) {
            throw reject(AudioRejectReason.TOTAL_DURATION_EXCEEDED);
        }

        acceptedTotalBytes += pcm.length;
        return new AudioChunk(pcm, impliedDurationMs);
    }

    public long acceptedDurationMs() {
        return acceptedTotalBytes / spec.bytesPerMillisecond();
    }

    /** Resets the per-turn cumulative budget; called between turns by the service. */
    public void reset() {
        acceptedTotalBytes = 0;
    }

    private static AudioRejectionException reject(AudioRejectReason reason) {
        log.debug("voice audio frame rejected: {}", reason);
        return new AudioRejectionException(reason);
    }
}
