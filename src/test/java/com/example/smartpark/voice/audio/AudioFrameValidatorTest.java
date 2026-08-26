package com.example.smartpark.voice.audio;

import com.example.smartpark.voice.model.VoiceSessionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioFrameValidatorTest {

    private static final int BYTES_PER_MS = 32; // 16000 Hz * 2 bytes

    private AudioFrameValidator newValidator() {
        return new AudioFrameValidator(
                new AudioFormatSpec(), Duration.ofSeconds(10), 8192, 1000);
    }

    private byte[] pcmMs(int milliseconds) {
        return new byte[BYTES_PER_MS * milliseconds];
    }

    @Test
    void specIsExactlySixteenKiloHertzMonoSignedPcm() {
        AudioFormatSpec spec = new AudioFormatSpec();
        assertThat(spec.sampleRate()).isEqualTo(16000);
        assertThat(spec.channels()).isEqualTo(1);
        assertThat(spec.sampleSizeBits()).isEqualTo(16);
        assertThat(spec.bytesPerMillisecond()).isEqualTo(BYTES_PER_MS);
    }

    @Test
    void acceptsValidPcmFramesOnlyWhileListening() {
        AudioFrameValidator validator = newValidator();

        AudioChunk chunk = validator.validate(pcmMs(20), VoiceSessionState.LISTENING);
        assertThat(chunk.lengthBytes()).isEqualTo(BYTES_PER_MS * 20);
        assertThat(chunk.durationMs()).isEqualTo(20);
    }

    @Test
    void rejectsAudioOutsideListeningState() {
        AudioFrameValidator validator = newValidator();
        for (VoiceSessionState state : VoiceSessionState.values()) {
            if (state == VoiceSessionState.LISTENING) {
                continue;
            }
            assertThatThrownBy(() -> validator.validate(pcmMs(20), state))
                    .as("state %s must not accept audio", state)
                    .isInstanceOf(AudioRejectionException.class)
                    .hasFieldOrPropertyWithValue("reason", AudioRejectReason.NOT_ACCEPTING_AUDIO);
        }
    }

    @Test
    void rejectsEmptyPayload() {
        AudioFrameValidator validator = newValidator();
        assertThatThrownBy(() -> validator.validate(new byte[0], VoiceSessionState.LISTENING))
                .isInstanceOf(AudioRejectionException.class)
                .hasFieldOrPropertyWithValue("reason", AudioRejectReason.EMPTY_PAYLOAD);
    }

    @Test
    void rejectsOddLengthPayloadThatCannotBeWholeInt16Samples() {
        AudioFrameValidator validator = newValidator();
        assertThatThrownBy(() -> validator.validate(new byte[641], VoiceSessionState.LISTENING))
                .isInstanceOf(AudioRejectionException.class)
                .hasFieldOrPropertyWithValue("reason", AudioRejectReason.MALFORMED_PCM);
    }

    @Test
    void rejectsSingleFrameAboveConfiguredByteLimit() {
        AudioFrameValidator validator = newValidator();
        // 8194 bytes is an even number of samples but exceeds the 8192-byte frame limit.
        assertThatThrownBy(() -> validator.validate(new byte[8194], VoiceSessionState.LISTENING))
                .isInstanceOf(AudioRejectionException.class)
                .hasFieldOrPropertyWithValue("reason", AudioRejectReason.FRAME_TOO_LARGE);
    }

    @Test
    void rejectsFramesImplyingExcessivelyFastDelivery() {
        AudioFrameValidator validator = newValidator();
        // One second is allowed per frame; anything implying a longer burst is rejected.
        assertThatThrownBy(() -> validator.validate(pcmMs(1001), VoiceSessionState.LISTENING))
                .isInstanceOf(AudioRejectionException.class)
                .hasFieldOrPropertyWithValue("reason", AudioRejectReason.EXCESSIVE_FRAME_DURATION);
    }

    @Test
    void rejectsCumulativeInputBeyondTenSeconds() {
        AudioFrameValidator validator = newValidator();
        // Fill exactly ten seconds using 20 ms frames within the per-frame limits.
        for (int i = 0; i < 500; i++) {
            validator.validate(pcmMs(20), VoiceSessionState.LISTENING);
        }
        assertThat(validator.acceptedDurationMs()).isEqualTo(10000);
        assertThatThrownBy(() -> validator.validate(pcmMs(20), VoiceSessionState.LISTENING))
                .isInstanceOf(AudioRejectionException.class)
                .hasFieldOrPropertyWithValue("reason", AudioRejectReason.TOTAL_DURATION_EXCEEDED);
    }

    @Test
    void bufferKeepsEverythingInMemoryAndTransfersOwnershipOnCommit() {
        AudioFrameValidator validator = newValidator();
        VoiceAudioRingBuffer buffer = new VoiceAudioRingBuffer(
                new AudioFormatSpec(), Duration.ofSeconds(10));

        byte[] first = pcmMs(20);
        byte[] second = pcmMs(30);
        buffer.append(validator.validate(first, VoiceSessionState.LISTENING));
        buffer.append(validator.validate(second, VoiceSessionState.LISTENING));
        assertThat(buffer.sizeBytes())
                .isEqualTo(BYTES_PER_MS * (20 + 30));

        byte[] snapshot = buffer.snapshot();
        assertThat(snapshot).hasSize((int) buffer.sizeBytes());

        // Ownership transfer: caller takes the snapshot, buffer must not retain the audio.
        buffer.release();
        assertThat(buffer.sizeBytes()).isZero();
        byte[] backing = buffer.backingArrayForLeakCheckOnly();
        boolean allZero = true;
        for (byte b : backing) {
            allZero &= b == 0;
        }
        assertThat(allZero)
                .as("released backing array must be zeroed so no PCM bytes survive")
                .isTrue();
        assertThat(buffer.snapshot()).isEmpty();
    }

    @Test
    void appendBeyondCapacityIsRejectedInsteadOfSilentlyWrapping() {
        AudioFormatSpec spec = new AudioFormatSpec();
        VoiceAudioRingBuffer buffer = new VoiceAudioRingBuffer(spec, Duration.ofMillis(40));
        buffer.append(new AudioChunk(pcmMs(25), 25));
        assertThatThrownBy(() -> buffer.append(new AudioChunk(pcmMs(25), 25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkNeverHandsOutItsInternalArray() {
        byte[] payload = pcmMs(20);
        payload[0] = 7;
        AudioChunk chunk = new AudioChunk(payload, 20);
        chunk.data()[0] = 42;
        assertThat(payload[0]).isEqualTo((byte) 7);
    }
}
