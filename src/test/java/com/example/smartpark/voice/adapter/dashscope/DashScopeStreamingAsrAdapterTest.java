package com.example.smartpark.voice.adapter.dashscope;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionOptions;
import com.alibaba.cloud.ai.dashscope.audio.transcription.RecognitionResult;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingAsrPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter contract tests against a local fake SDK facade — no network access.
 * Real online smoke lives in the hardening plan, not default unit tests.
 */
class DashScopeStreamingAsrAdapterTest {

    private static final String PROVIDER_DETAIL = "secret-provider-trace-42";

    private CapturingFacade facade;
    private DashScopeStreamingAsrAdapter adapter;

    @AfterEach
    void shutdownAdapter() {
        if (adapter != null) {
            adapter.destroy();
        }
    }

    private void newAdapter() {
        facade = new CapturingFacade();
        adapter = new DashScopeStreamingAsrAdapter(facade);
    }

    @Test
    void streamsAudioToSdkAndCompletesInputOnCommit() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", listener);

        adapter.send("s-1", "t-1", new byte[]{1, 2});
        adapter.send("s-1", "t-1", new byte[]{3, 4});
        adapter.commit("s-1", "t-1");

        List<ByteBuffer> seen = new CopyOnWriteArrayList<>();
        List<String> inputTerminal = new CopyOnWriteArrayList<>();
        facade.capturedAudio.subscribe(
                buffer -> seen.add(buffer),
                error -> inputTerminal.add("error"),
                () -> inputTerminal.add("complete"));
        await(() -> inputTerminal.size() == 1);

        assertThat(decode(seen)).containsExactly("\u0001\u0002", "\u0003\u0004");
        assertThat(inputTerminal).containsExactly("complete");
        assertThat(facade.capturedOptions.getSampleRate()).isEqualTo(16000);
        assertThat(facade.capturedOptions.getFormat()).isEqualToIgnoringCase("pcm");
    }

    @Test
    void serializesPartialsThenFinalThenCloseInCallbackOrder() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", listener);

        facade.emit(sentence("你好", false));
        facade.emit(sentence("你好世", false));
        facade.emit(sentence("你好世界", true));
        facade.complete();

        await(() -> listener.events.size() == 4);
        assertThat(listener.events).containsExactly(
                "PARTIAL:你好", "PARTIAL:你好世", "FINAL:你好世界", "CLOSED");
    }

    @Test
    void mapsProviderErrorsToSafeErrorCodeWithoutLeakingDetail() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", listener);

        facade.fail(new RuntimeException(PROVIDER_DETAIL));

        await(() -> listener.events.size() == 2);
        assertThat(listener.events).containsExactly(
                "ERROR:" + VoiceErrorCode.PROVIDER_FAILURE, "CLOSED");
        assertThat(String.join("|", listener.events)).doesNotContain(PROVIDER_DETAIL);
    }

    @Test
    void cancelDisposesUpstreamAndDropsLateCallbacks() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", listener);

        adapter.send("s-1", "t-1", new byte[]{9});
        adapter.cancel("s-1", "t-1");

        await(() -> facade.cancelled.get());
        // Late provider events after cancellation must not reach the listener.
        facade.emit(sentence("晚到", true));
        facade.complete();

        await(() -> facade.completedOrFailed.get());
        Thread.yield();
        assertThat(listener.events).doesNotContain("FINAL:晚到");
        assertThat(listener.events).isEmpty();
    }

    @Test
    void rejectsStartingASecondTurnWhileOneIsActive() {
        newAdapter();
        adapter.start("s-1", "t-1", new RecordingListener());
        assertThatThrownBy(() -> adapter.start("s-1", "t-2", new RecordingListener()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> decode(List<ByteBuffer> buffers) {
        return buffers.stream()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.asReadOnlyBuffer().get(bytes);
                    return new String(bytes, StandardCharsets.ISO_8859_1);
                })
                .toList();
    }

    private static RecognitionResult sentence(String text, boolean end) {
        return new RecognitionResult(new RecognitionResult.Sentence(
                null, null, null, text, List.of(), null, null,
                null, null, null, null, null, null, null, null, end, null), null);
    }

    private static void await(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within timeout");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Fake standing in for the DashScope SDK surface used by the adapter. */
    private static final class CapturingFacade implements DashScopeStreamingAsrAdapter.AsrSdkFacade {
        final Sinks.Many<ByteBuffer> audioSink =
                Sinks.many().unicast().onBackpressureBuffer();
        final Flux<ByteBuffer> capturedAudioView = audioSink.asFlux();

        volatile Flux<ByteBuffer> capturedAudio;
        volatile DashScopeAudioTranscriptionOptions capturedOptions;
        final Sinks.Many<RecognitionResult> outbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean completedOrFailed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public Flux<RecognitionResult> streamRecognition(
                Flux<ByteBuffer> audio, DashScopeAudioTranscriptionOptions options) {
            this.capturedAudio = audio;
            this.capturedOptions = options;
            return outbound.asFlux()
                    .doOnCancel(() -> cancelled.set(true))
                    .doFinally(signal -> completedOrFailed.set(true));
        }

        void emit(RecognitionResult result) {
            outbound.tryEmitNext(result);
        }

        void complete() {
            outbound.tryEmitComplete();
        }

        void fail(Throwable error) {
            outbound.tryEmitError(error);
        }
    }

    private static final class RecordingListener implements StreamingAsrPort.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onPartial(String sessionId, String turnId, String text) {
            events.add("PARTIAL:" + text);
        }

        @Override
        public void onFinal(String sessionId, String turnId, String text) {
            events.add("FINAL:" + text);
        }

        @Override
        public void onError(String sessionId, String turnId, VoiceErrorCode code) {
            events.add("ERROR:" + code);
        }

        @Override
        public void onClosed(String sessionId, String turnId) {
            events.add("CLOSED");
        }
    }
}
