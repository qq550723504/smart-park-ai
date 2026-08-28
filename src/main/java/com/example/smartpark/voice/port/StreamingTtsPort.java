package com.example.smartpark.voice.port;

import com.example.smartpark.voice.model.VoiceErrorCode;

import java.util.List;

/**
 * Contract for real streaming TTS providers. Only text that already passed
 * {@code VoiceAnswerValidator} may be synthesized; implementations must
 * serialize callbacks onto a single thread, number chunks from one, and drop
 * every callback for a cancelled or superseded turn.
 */
public interface StreamingTtsPort {

    /**
     * Begins speaking for one turn. Text segments are merged in order into the
     * provider input stream; at most one active TTS turn per session id.
     */
    void start(String sessionId, String turnId, List<String> textSegments, Listener listener);

    /** Cancels playback for the turn; later callbacks for it are dropped. */
    void cancel(String sessionId, String turnId);

    interface Listener {

        /** chunkSequence starts at 1 and increases by one per chunk. */
        void onAudioChunk(String sessionId, String turnId, int chunkSequence, byte[] audio);

        /** Safe code only; raw provider exceptions never cross this boundary. Terminal. */
        void onError(String sessionId, String turnId, VoiceErrorCode code);

        void onCompleted(String sessionId, String turnId);

        /** Published exactly once when output is interrupted mid-stream. Terminal. */
        void onInterrupted(String sessionId, String turnId);
    }
}
