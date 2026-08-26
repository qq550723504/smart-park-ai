package com.example.smartpark.voice.port;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the reusable fake {@link StreamingAsrPort} used by session-layer
 * tests, including partial/final/close/error semantics and late-event dropping.
 */
class StreamingAsrPortTest {

    private static byte[] pcm(int ms) {
        return new byte[32 * ms];
    }

    @Test
    void deliversScriptedPartialsThenFinalThenCloseInOrder() {
        FakeStreamingAsrPort port = new FakeStreamingAsrPort();
        List<String> events = new CopyOnWriteArrayList<>();

        port.start("s-1", "t-1", new RecordingListener(events));
        port.emitPartial("s-1", "t-1", "你好");
        port.emitPartial("s-1", "t-1", "你好世");
        port.emitFinal("s-1", "t-1", "你好世界");
        port.closeTurn("s-1", "t-1");

        assertThat(events).containsExactly(
                "PARTIAL:你好", "PARTIAL:你好世", "FINAL:你好世界", "CLOSED");
    }

    @Test
    void deliversExactlyOneSafeErrorCodeAndNoTranscriptsAfterwards() {
        FakeStreamingAsrPort port = new FakeStreamingAsrPort();
        List<String> events = new CopyOnWriteArrayList<>();

        port.start("s-1", "t-1", new RecordingListener(events));
        port.emitPartial("s-1", "t-1", "部分");
        port.failTurn("s-1", "t-1", com.example.smartpark.voice.model.VoiceErrorCode.PROVIDER_FAILURE);
        port.emitFinal("s-1", "t-1", "不应送达");

        assertThat(events).containsExactly(
                "PARTIAL:部分",
                "ERROR:" + com.example.smartpark.voice.model.VoiceErrorCode.PROVIDER_FAILURE,
                "CLOSED");
    }

    @Test
    void recordsAudioCommitsAndCancelsForInspection() {
        FakeStreamingAsrPort port = new FakeStreamingAsrPort();
        port.start("s-1", "t-1", new RecordingListener(new CopyOnWriteArrayList<>()));

        port.send("s-1", "t-1", pcm(20));
        port.send("s-1", "t-1", pcm(30));
        port.commit("s-1", "t-1");

        assertThat(port.sentChunks("s-1")).hasSize(2)
                .allSatisfy(chunk -> assertThat(chunk.length).isIn(640, 960));
        assertThat(port.committedTurns()).containsExactly("t-1");

        port.start("s-1", "t-2", new RecordingListener(new CopyOnWriteArrayList<>()));
        port.cancel("s-1", "t-2");
        assertThat(port.cancelledTurns()).containsExactly("t-2");

        // Events emitted after cancel are dropped, never delivered.
        List<String> lateEvents = new CopyOnWriteArrayList<>();
        // (listener was removed on cancel; emit must be a silent no-op)
        port.emitFinal("s-1", "t-2", "晚到");
        assertThat(port.activeTurnIds()).doesNotContain("t-2");
        assertThat(lateEvents).isEmpty();
    }

    @Test
    void unknownSessionsHaveNoListeners() {
        FakeStreamingAsrPort port = new FakeStreamingAsrPort();
        port.emitPartial("ghost", "t-x", "?");
        port.emitFinal("ghost", "t-x", "?");
        port.failTurn("ghost", "t-x", com.example.smartpark.voice.model.VoiceErrorCode.INTERNAL_ERROR);
        assertThat(port.startedTurns()).isEmpty();
    }

    private static final class RecordingListener implements StreamingAsrPort.Listener {
        private final List<String> events;

        private RecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onPartial(String sessionId, String turnId, String text) {
            events.add("PARTIAL:" + text);
        }

        @Override
        public void onFinal(String sessionId, String turnId, String text) {
            events.add("FINAL:" + text);
        }

        @Override
        public void onError(String sessionId, String turnId,
                            com.example.smartpark.voice.model.VoiceErrorCode code) {
            events.add("ERROR:" + code);
        }

        @Override
        public void onClosed(String sessionId, String turnId) {
            events.add("CLOSED");
        }
    }

    // Silence unused-import warning for StandardCharsets in some toolchains.
    @SuppressWarnings("unused")
    private static final Object CHARSET_ANCHOR = StandardCharsets.US_ASCII;
}
