package com.example.smartpark.voice;

import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.FakeStreamingTtsPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-turn cancellation semantics: interrupting one session's TTS never
 * affects other sessions or later turns; cancellation is idempotent and
 * publishes OUTPUT_INTERRUPTED-equivalent exactly once.
 */
class VoiceOutputCancellationTest {

    private final FakeStreamingTtsPort port = new FakeStreamingTtsPort();

    @Test
    void micClickCancellationStopsCurrentTurnOnly() {
        RecordingListener currentTurn = new RecordingListener();
        RecordingListener nextTurn = new RecordingListener();

        port.start("s-1", "t-1", List.of("第一轮回答"), currentTurn);
        port.cancel("s-1", "t-1"); // 用户点击麦克风：先中断当前 TTS

        // 同一会话的下一轮不受影响。
        port.start("s-1", "t-2", List.of("第二轮回答"), nextTurn);
        port.emitChunk("s-1", "t-2", new byte[]{1});
        port.completeTurn("s-1", "t-2");

        assertThat(currentTurn.events).containsExactly("INTERRUPTED");
        assertThat(nextTurn.events).containsExactly("CHUNK:1", "COMPLETED");
    }

    @Test
    void cancellationIsIdempotentAcrossSessions() {
        RecordingListener a = new RecordingListener();
        RecordingListener b = new RecordingListener();

        port.start("s-a", "t-1", List.of("甲"), a);
        port.start("s-b", "t-2", List.of("乙"), b);

        port.cancel("s-a", "t-1");
        port.cancel("s-a", "t-1"); // repeated click

        assertThat(a.events).containsExactly("INTERRUPTED");
        assertThat(b.events).isEmpty();
    }

    @Test
    void cancellingUnknownTurnIsANoOp() {
        RecordingListener listener = new RecordingListener();
        port.start("s-1", "t-1", List.of("正常播放"), listener);

        port.cancel("s-1", "t-other");
        port.emitChunk("s-1", "t-1", new byte[]{2});
        port.completeTurn("s-1", "t-1");

        assertThat(listener.events).containsExactly("CHUNK:1", "COMPLETED");
    }

    private static final class RecordingListener implements StreamingTtsPort.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();

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
