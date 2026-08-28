package com.example.smartpark.voice.port;

import com.example.smartpark.voice.model.VoiceErrorCode;

/**
 * Contract for real streaming ASR providers. Application/session code depends
 * only on this port — never on vendor SDK types. Implementations must serialize
 * callbacks onto a single thread before invoking the listener and must drop
 * every callback for a turn that was cancelled or superseded by its turnId.
 */
public interface StreamingAsrPort {

    /** Begins one recognition turn; at most one active turn per session id. */
    void start(String sessionId, String turnId, Listener listener);

    /** Streams one PCM chunk into the active turn; ownership passes to the port. */
    void send(String sessionId, String turnId, byte[] pcmChunk);

    /** Signals end-of-input; the provider flushes and delivers the final transcript. */
    void commit(String sessionId, String turnId);

    /** Cancels the turn; later callbacks for this turn are dropped. */
    void cancel(String sessionId, String turnId);

    /**
     * Terminal event rules: exactly one of FINAL+CLOSED, ERROR+CLOSED, or CLOSED
     * arrives per turn; after ERROR/FINAL no transcript events may follow.
     */
    interface Listener {

        void onPartial(String sessionId, String turnId, String text);

        void onFinal(String sessionId, String turnId, String text);

        /** Carries safe codes only; raw provider exceptions never cross this boundary. */
        void onError(String sessionId, String turnId, VoiceErrorCode code);

        void onClosed(String sessionId, String turnId);
    }
}
