package com.example.smartpark.voice.port;

import com.example.smartpark.voice.model.VoiceErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the reusable fake {@link StreamingTtsPort} used by session-layer
 * tests: first/subsequent chunk ordering, completion, safe error codes, and
 * cancellation dropping every later callback.
 */
class StreamingTtsPortTest {

    private static byte[] audio(int marker) {
        return new byte[]{(byte) marker};
    }

    @Test
    void deliversFirstThenSubsequentChunksInOrderThenCompletion() {
        FakeStreamingTtsPort port = new FakeStreamingTtsPort();
        RecordingListener listener = new RecordingListener();

        port.start("s-1", "t-1", List.of("你好", "世界"), listener);
        port.emitChunk("s-1", "t-1", audio(1));
        port.emitChunk("s-1", "t-1", audio(2));
        port.completeTurn("s-1", "t-1");

        assertThat(listener.events).containsExactly(
                "CHUNK:1", "CHUNK:2", "COMPLETED");
        assertThat(port.requestedTexts("s-1")).containsExactly("你好", "世界");
    }

    @Test
    void providerErrorDeliversSafeCodeOnceAndNothingAfterwards() {
        FakeStreamingTtsPort port = new FakeStreamingTtsPort();
        RecordingListener listener = new RecordingListener();

        port.start("s-1", "t-1", List.of("文本"), listener);
        port.failTurn("s-1", "t-1", VoiceErrorCode.PROVIDER_FAILURE);
        port.emitChunk("s-1", "t-1", audio(9));
        port.completeTurn("s-1", "t-1");

        assertThat(listener.events).containsExactly("ERROR:" + VoiceErrorCode.PROVIDER_FAILURE);
    }

    @Test
    void interruptPublishesOnceAndDropsAllLateChunks() {
        FakeStreamingTtsPort port = new FakeStreamingTtsPort();
        RecordingListener listener = new RecordingListener();

        port.start("s-1", "t-1", List.of("长句"), listener);
        port.interruptTurn("s-1", "t-1");
        port.interruptTurn("s-1", "t-1"); // idempotent
        port.emitChunk("s-1", "t-1", audio(3));
        port.completeTurn("s-1", "t-1");

        assertThat(listener.events).containsExactly("INTERRUPTED");
    }

    @Test
    void unknownSessionsAreSilentlyIgnored() {
        FakeStreamingTtsPort port = new FakeStreamingTtsPort();
        RecordingListener listener = new RecordingListener();
        port.emitChunk("ghost", "t-x", audio(0));
        port.completeTurn("ghost", "t-x");
        port.failTurn("ghost", "t-x", VoiceErrorCode.INTERNAL_ERROR);
        port.interruptTurn("ghost", "t-x");
        assertThat(listener.events).isEmpty();
    }

    private static final class RecordingListener implements StreamingTtsPort.Listener {
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onAudioChunk(String sessionId, String turnId, int chunkSequence, byte[] audio) {
            events.add("CHUNK:" + chunkSequence);
        }

        @Override
        public void onError(String sessionId, String turnId, VoiceErrorCode code) {
            events.add("ERROR:" + code);
        }

        @Override
        public void onCompleted(String sessionId, String turnId) {
            events.add("COMPLETED");
        }

        @Override
        public void onInterrupted(String sessionId, String turnId) {
            events.add("INTERRUPTED");
        }
    }
}
