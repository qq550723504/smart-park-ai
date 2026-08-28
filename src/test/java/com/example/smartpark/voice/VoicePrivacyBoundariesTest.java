package com.example.smartpark.voice;

import com.example.smartpark.voice.model.AnswerDeltaFrame;
import com.example.smartpark.voice.model.AsrFinalFrame;
import com.example.smartpark.voice.model.AsrPartialFrame;
import com.example.smartpark.voice.model.AudioChunkFrame;
import com.example.smartpark.voice.model.ErrorFrame;
import com.example.smartpark.voice.model.SessionStateFrame;
import com.example.smartpark.voice.model.ToolEventFrame;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy regression for the voice protocol layer: JSON frame DTOs must never
 * carry raw PCM bytes, base64 payloads, API keys or original prompts. Raw audio
 * travels only as binary WebSocket messages outside the event/DTO surface.
 */
class VoicePrivacyBoundariesTest {

    private static final Set<Class<?>> SERVER_FRAMES = Set.of(
            SessionStateFrame.class,
            AsrPartialFrame.class,
            AsrFinalFrame.class,
            ToolEventFrame.class,
            AnswerDeltaFrame.class,
            AudioChunkFrame.class,
            ErrorFrame.class);

    @Test
    void serverFramesDeclareNoRawAudioByteComponents() {
        for (Class<?> frame : SERVER_FRAMES) {
            for (var component : frame.getDeclaredConstructors()) {
                assertThat(Arrays.stream(component.getParameterTypes()).noneMatch(
                        type -> type == byte[].class))
                        .as("%s must not declare byte[] components", frame.getSimpleName())
                        .isTrue();
            }
        }
    }

    @Test
    void audioChunkFrameAnnouncesSizeOnly() {
        var frame = new AudioChunkFrame("s", "m", 0, 3, 640);
        // 帧只公告序号与大小；PCM 本体走二进制通道，绝不进事件或日志。
        assertThat(frame.chunkSequence()).isEqualTo(3);
        assertThat(frame.sizeBytes()).isEqualTo(640);
    }

    @Test
    void toolEventsCarryNamesAndPhaseButNoArgumentsOrDigests() throws Exception {
        var fields = Arrays.stream(ToolEventFrame.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(fields).containsExactlyInAnyOrder(
                "sessionId", "messageId", "sequence", "toolName", "phase", "argumentSummary");
        // 不允许出现 prompt / payload / digest 之类的字段名。
        assertThat(fields).noneMatch(name ->
                name.toLowerCase().contains("prompt")
                        || name.toLowerCase().contains("payload")
                        || name.toLowerCase().contains("digest"));
    }
}
