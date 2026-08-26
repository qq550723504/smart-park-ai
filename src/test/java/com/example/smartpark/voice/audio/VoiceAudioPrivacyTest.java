package com.example.smartpark.voice.audio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.smartpark.voice.model.VoiceSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceAudioPrivacyTest {

    private static final int BYTES_PER_MS = 32;
    private static final String AUDIO_MARK = "VOICE-AUDIO-MARKER";

    private ListAppender<ILoggingEvent> appender;
    private Logger voiceAudioLogger;
    private Level originalLevel;

    @BeforeEach
    void captureVoiceAudioLogs() {
        voiceAudioLogger = (Logger) LoggerFactory.getLogger(
                "com.example.smartpark.voice.audio");
        originalLevel = voiceAudioLogger.getLevel();
        voiceAudioLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        voiceAudioLogger.addAppender(appender);
    }

    @AfterEach
    void detachCapture() {
        voiceAudioLogger.detachAppender(appender);
        voiceAudioLogger.setLevel(originalLevel);
    }

    @Test
    void rejectionsNeverLeakPcmBytesOrTheirEncodings() {
        AudioFrameValidator validator = new AudioFrameValidator(
                new AudioFormatSpec(), Duration.ofSeconds(10), 8192, 1000);

        byte[] markedAudio = markedAudio(200);
        String base64Mark = Base64.getEncoder().encodeToString(markedAudio);

        // Trigger every rejection path with the marked payload where possible.
        for (com.example.smartpark.voice.model.VoiceSessionState state :
                com.example.smartpark.voice.model.VoiceSessionState.values()) {
            if (state != VoiceSessionState.LISTENING) {
                tryRejection(() -> validator.validate(markedAudio, state));
            }
        }
        tryRejection(() -> validator.validate(new byte[0], VoiceSessionState.LISTENING));
        tryRejection(() -> validator.validate(new byte[641], VoiceSessionState.LISTENING));
        tryRejection(() -> validator.validate(new byte[8193], VoiceSessionState.LISTENING));
        tryRejection(() -> validator.validate(markedAudio(1001), VoiceSessionState.LISTENING));
        tryRejection(() -> validator.validate(new byte[320_001], VoiceSessionState.IDLE));

        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();
        for (ILoggingEvent event : events) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted)
                    .as("log line must not contain raw PCM bytes or their base64 encoding")
                    .doesNotContain(AUDIO_MARK)
                    .doesNotContain(base64Mark);
            assertThat(event.getLevel()).isNotEqualTo(Level.TRACE);
            assertThat(formatted).doesNotContain("[B@");
        }
    }

    @Test
    void exceptionMessagesCarryReasonOnlyAndNoPayload() {
        AudioFrameValidator validator = new AudioFrameValidator(
                new AudioFormatSpec(), Duration.ofSeconds(10), 8192, 1000);
        byte[] markedAudio = markedAudio(20);
        String base64Mark = Base64.getEncoder().encodeToString(markedAudio);

        try {
            validator.validate(markedAudio, VoiceSessionState.SPEAKING);
        } catch (AudioRejectionException exception) {
            assertThat(exception.getMessage()).doesNotContain(AUDIO_MARK);
            assertThat(exception.getMessage()).doesNotContain(base64Mark);
        }
    }

    private void tryRejection(Runnable rejection) {
        try {
            rejection.run();
        } catch (AudioRejectionException expected) {
            // logged; assertions happen afterwards
        }
    }

    /**
     * Builds PCM whose content is a repeating ASCII marker so that any accidental
     * logging of bytes, strings derived from them, or their base64 would be detectable.
     */
    private static byte[] markedAudio(int milliseconds) {
        byte[] audio = new byte[BYTES_PER_MS * milliseconds];
        byte[] mark = AUDIO_MARK.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < audio.length; i++) {
            audio[i] = mark[i % mark.length];
        }
        return audio;
    }
}
