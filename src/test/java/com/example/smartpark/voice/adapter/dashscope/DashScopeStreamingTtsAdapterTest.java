package com.example.smartpark.voice.adapter.dashscope;

import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechOptions;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapter contract tests against a local fake SDK facade — no network access.
 */
class DashScopeStreamingTtsAdapterTest {

    private static final String PROVIDER_DETAIL = "secret-tts-trace-7";

    private CapturingFacade facade;
    private DashScopeStreamingTtsAdapter adapter;
    private DashScopeAudioSpeechOptions configuredOptions;

    @AfterEach
    void shutdownAdapter() {
        if (adapter != null) {
            adapter.destroy();
        }
    }

    private void newAdapter() {
        facade = new CapturingFacade();
        configuredOptions = new DashScopeAudioSpeechOptions();
        configuredOptions.setModel("cosyvoice-v2");
        configuredOptions.setVoice("longxiaochun_v2");
        configuredOptions.setSpeed(1.25);
        adapter = new DashScopeStreamingTtsAdapter(facade, configuredOptions);
    }

    @Test
    void mergesTextSegmentsInOrderIntoProviderInput() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", List.of("你好", "，", "世界"), listener);

        await(() -> facade.receivedTexts.size() == 3);
        assertThat(facade.receivedTexts).containsExactly("你好", "，", "世界");
        assertThat(facade.capturedOptions).isInstanceOf(DashScopeAudioSpeechOptions.class);
        DashScopeAudioSpeechOptions passed = (DashScopeAudioSpeechOptions) facade.capturedOptions;
        assertThat(passed.getModel()).isEqualTo("cosyvoice-v2");
        assertThat(passed.getVoice()).isEqualTo("longxiaochun_v2");
        assertThat(passed.getSpeed()).isEqualTo(1.25);
        assertThat(passed).isNotSameAs(configuredOptions);
    }

    @Test
    void numbersAudioChunksFromOneInCallbackOrder() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", List.of("长句"), listener);

        facade.emit(speech(new byte[]{1}));
        facade.emit(speech(new byte[]{2}));
        facade.emit(speech(new byte[]{3}));
        facade.complete();

        await(() -> listener.events.size() == 4);
        assertThat(listener.events).containsExactly("CHUNK:1", "CHUNK:2", "CHUNK:3", "COMPLETED");
    }

    @Test
    void mapsProviderErrorsToSafeErrorCodeWithoutLeakingDetail() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", List.of("文本"), listener);

        facade.fail(new RuntimeException(PROVIDER_DETAIL));

        await(() -> listener.events.size() == 1);
        assertThat(listener.events).containsExactly("ERROR:" + VoiceErrorCode.PROVIDER_FAILURE);
        assertThat(String.join("|", listener.events)).doesNotContain(PROVIDER_DETAIL);
    }

    @Test
    void cancelDisposesUpstreamPublishesInterruptedOnceAndDropsLateCallbacks() {
        newAdapter();
        RecordingListener listener = new RecordingListener();
        adapter.start("s-1", "t-1", List.of("很长的一句话"), listener);

        adapter.cancel("s-1", "t-1");

        await(() -> listener.events.size() == 1);
        assertThat(listener.events).containsExactly("INTERRUPTED");
        await(() -> facade.cancelled.get());

        // Late provider emissions after cancellation must not reach the listener.
        facade.emit(speech(new byte[]{9}));
        facade.complete();
        Thread.yield();
        assertThat(listener.events).containsExactly("INTERRUPTED");
    }

    @Test
    void cancellingOneTurnDoesNotAffectAnotherSession() {
        newAdapter();
        RecordingListener listenerA = new RecordingListener();
        RecordingListener listenerB = new RecordingListener();
        adapter.start("s-a", "t-a", List.of("甲"), listenerA);
        adapter.start("s-b", "t-b", List.of("乙"), listenerB);

        adapter.cancel("s-a", "t-a");
        await(() -> listenerA.events.contains("INTERRUPTED"));

        facade.emit(speech(new byte[]{5}));
        facade.complete();
        await(() -> listenerB.events.size() == 2);
        assertThat(listenerB.events).containsExactly("CHUNK:1", "COMPLETED");
        assertThat(listenerA.events).doesNotContain("CHUNK:1", "COMPLETED");
    }

    @Test
    void rejectsStartingASecondTurnWhileOneIsActiveForSameSession() {
        newAdapter();
        adapter.start("s-1", "t-1", List.of("一"), new RecordingListener());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.start("s-1", "t-2", List.of("二"), new RecordingListener()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static TextToSpeechResponse speech(byte[] audio) {
        return new TextToSpeechResponse(List.of(new Speech(audio)));
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

    /** Fake standing in for the DashScope TTS SDK surface used by the adapter. */
    private static final class CapturingFacade implements DashScopeStreamingTtsAdapter.TtsSdkFacade {
        final CopyOnWriteArrayList<String> receivedTexts = new CopyOnWriteArrayList<>();
        final Sinks.Many<TextToSpeechResponse> outbound =
                Sinks.many().multicast().onBackpressureBuffer();
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile org.springframework.ai.audio.tts.TextToSpeechOptions capturedOptions;

        @Override
        public Flux<TextToSpeechResponse> streamSpeech(
                Flux<String> text, org.springframework.ai.audio.tts.TextToSpeechOptions options) {
            capturedOptions = options;
            text.subscribe(receivedTexts::add, error -> { /* drained by test */ });
            return outbound.asFlux()
                    .doOnCancel(() -> cancelled.set(true));
        }

        void emit(TextToSpeechResponse response) {
            outbound.tryEmitNext(response);
        }

        void complete() {
            outbound.tryEmitComplete();
        }

        void fail(Throwable error) {
            outbound.tryEmitError(error);
        }
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
