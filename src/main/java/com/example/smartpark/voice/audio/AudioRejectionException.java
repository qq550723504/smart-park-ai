package com.example.smartpark.voice.audio;

/**
 * Raised for every rejected audio frame. Messages intentionally carry only the
 * rejection reason — never payload bytes, their base64, or provider detail.
 */
public final class AudioRejectionException extends RuntimeException {

    private final AudioRejectReason reason;

    public AudioRejectionException(AudioRejectReason reason) {
        super("audio frame rejected: " + reason);
        this.reason = reason;
    }

    public AudioRejectReason reason() {
        return reason;
    }
}
