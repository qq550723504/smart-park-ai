package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.workflow.CustomerConversation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCustomerSessionStoreTest {

    @Test
    void savesAndReadsASession() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerSessionStore store = new InMemoryCustomerSessionStore(clock, 10, Duration.ofHours(1));
        CustomerSessionStore.SessionSnapshot expected = store.create(
                "cs-1", result("cs-1"), List.of(message("USER", "Where can I park?", clock.instant())), List.of(), clock.instant());

        assertThat(store.find("cs-1", clock.instant())).contains(expected);
        assertThat(store.count(clock.instant())).isEqualTo(1);
    }

    @Test
    void updatePersistsAppendedMultiTurnMessages() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerSessionStore store = new InMemoryCustomerSessionStore(clock, 10, Duration.ofHours(1));
        CustomerSessionStore.SessionSnapshot initial = store.create(
                "cs-1", result("cs-1"), List.of(message("USER", "Where can I park?", clock.instant())), List.of(), clock.instant());
        CustomerSessionStore.SessionSnapshot updated = new CustomerSessionStore.SessionSnapshot(
                initial.sessionId(), initial.result(), initial.createdAt(),
                List.of(
                        message("USER", "Where can I park?", clock.instant()),
                        message("ASSISTANT", "Use visitor parking.", clock.instant()),
                        message("USER", "How much does it cost?", clock.instant()),
                        message("ASSISTANT", "See the parking guide.", clock.instant())),
                List.of());

        store.update(updated);

        assertThat(store.find("cs-1", clock.instant()).orElseThrow().messages())
                .extracting(CustomerConversation.Message::role)
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
    }

    @Test
    void sessionStoreExpiresSessionsAndIdempotencyTogether() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerSessionStore store =
                new InMemoryCustomerSessionStore(clock, 10, Duration.ofMinutes(5));
        store.create("cs-1", result("cs-1"), List.of(), List.of(), clock.instant());
        store.rememberIdempotency("request-1", "same question", "cs-1", clock.instant());

        clock.advance(Duration.ofMinutes(6));

        assertThat(store.find("cs-1", clock.instant())).isEmpty();
        assertThat(store.findIdempotency("request-1", clock.instant())).isEmpty();
    }

    @Test
    void evictsTheOldestSessionWhenCapacityIsReached() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerSessionStore store = new InMemoryCustomerSessionStore(clock, 2, Duration.ofHours(1));
        store.create("cs-1", result("cs-1"), List.of(), List.of(), clock.instant());
        store.rememberIdempotency("request-1", "first", "cs-1", clock.instant());
        store.create("cs-2", result("cs-2"), List.of(), List.of(), clock.instant());
        store.create("cs-3", result("cs-3"), List.of(), List.of(), clock.instant());

        assertThat(store.find("cs-1", clock.instant())).isEmpty();
        assertThat(store.findIdempotency("request-1", clock.instant())).isEmpty();
        assertThat(store.find("cs-2", clock.instant())).isPresent();
        assertThat(store.find("cs-3", clock.instant())).isPresent();
    }

    @Test
    void reusesTheRememberedIdempotencyRecord() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
        InMemoryCustomerSessionStore store = new InMemoryCustomerSessionStore(clock, 10, Duration.ofHours(1));
        store.rememberIdempotency("request-1", "same question", "cs-1", clock.instant());

        assertThat(store.findIdempotency("request-1", clock.instant()))
                .contains(new CustomerSessionStore.IdempotencyRecord("same question", "cs-1", clock.instant()));
    }

    private static CustomerServiceResult result(String sessionId) {
        return new CustomerServiceResult(sessionId, "PARKING", "Use visitor parking.", List.of("KD-PARKING-001"), false, null);
    }

    private static CustomerConversation.Message message(String role, String text, Instant createdAt) {
        return new CustomerConversation.Message(role, text, createdAt);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
