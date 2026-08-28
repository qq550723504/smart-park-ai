package com.example.smartpark.voice;

import com.example.smartpark.voice.model.VoiceSessionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceSessionStoreTest {

    @Test
    void propagatesConfiguredInputDurationToSessionAudioBudget() {
        VoiceSessionStore store = new VoiceSessionStore(new VoiceDeadlines(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        VoiceSession session = store.create();
        try {
            assertThat(session.ringBuffer().remainingCapacity()).isEqualTo(32_000);
            for (int i = 0; i < 4; i++) {
                session.audioValidator().validate(new byte[8_000], VoiceSessionState.LISTENING);
            }
            assertThatThrownBy(() -> session.audioValidator().validate(
                    new byte[2], VoiceSessionState.LISTENING))
                    .hasMessageContaining("TOTAL_DURATION_EXCEEDED");
        } finally {
            store.remove(session.sessionId());
            session.close();
        }
    }

    @Test
    void activeSessionCapIsReservedAtomicallyAndReleasedOnRemoval() {
        VoiceSessionStore store = new VoiceSessionStore();
        List<VoiceSession> sessions = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                sessions.add(store.create());
            }
            assertThatThrownBy(store::create)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cap 200");
            assertThat(store.size()).isEqualTo(200);
        } finally {
            sessions.forEach(session -> {
                store.remove(session.sessionId());
                session.close();
            });
        }
        VoiceSession replacement = store.create();
        assertThat(replacement).isNotNull();
        store.remove(replacement.sessionId());
        replacement.close();
    }
}
