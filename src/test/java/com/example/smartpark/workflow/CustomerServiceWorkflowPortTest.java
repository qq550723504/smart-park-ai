package com.example.smartpark.workflow;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.model.customer.CustomerTicket;
import com.example.smartpark.model.customer.CustomerTicketStatus;
import com.example.smartpark.port.customer.CustomerSessionStore;
import com.example.smartpark.port.customer.CustomerTicketPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceWorkflowPortTest {

    @Test
    void workflowDelegatesTicketLifecycleAndSessionPersistenceToPorts() {
        List<String> events = new ArrayList<>();
        RecordingSessionStore sessions = new RecordingSessionStore(events);
        RecordingTicketPort tickets = new RecordingTicketPort(events);
        CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
                new MockParkFixture().knowledge(), sessions, tickets,
                Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
                () -> "cs-port-001");

        CustomerServiceResult created = workflow.handle("A1 洗手间漏水，需要报修");
        workflow.tickets();
        workflow.updateTicket(created.ticket().id(), "ASSIGNED");

        assertThat(tickets.created).isTrue();
        assertThat(tickets.listed).isTrue();
        assertThat(tickets.updated).isTrue();
        assertThat(sessions.created).isTrue();
        assertThat(sessions.updated).isTrue();
        assertThat(events).contains("ticket.update", "session.update");
        assertThat(events.indexOf("ticket.update")).isLessThan(events.indexOf("session.update"));
    }

    private static final class RecordingSessionStore implements CustomerSessionStore {
        private final Map<String, SessionSnapshot> sessions = new HashMap<>();
        private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
        private final List<String> events;
        private boolean created;
        private boolean updated;

        private RecordingSessionStore(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<SessionSnapshot> find(String sessionId, Instant now) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public Optional<IdempotencyRecord> findIdempotency(String key, Instant now) {
            return Optional.ofNullable(idempotency.get(key));
        }

        @Override
        public SessionSnapshot create(String sessionId, CustomerServiceResult result,
                                      List<CustomerConversation.Message> messages,
                                      List<CustomerConversation.RetrievalTrace> retrievals,
                                      Instant createdAt) {
            created = true;
            events.add("session.create");
            SessionSnapshot snapshot = new SessionSnapshot(sessionId, result, createdAt, messages, retrievals);
            sessions.put(sessionId, snapshot);
            return snapshot;
        }

        @Override
        public SessionSnapshot update(SessionSnapshot snapshot) {
            updated = true;
            events.add("session.update");
            sessions.put(snapshot.sessionId(), snapshot);
            return snapshot;
        }

        @Override
        public void rememberIdempotency(String key, IdempotencyScope scope, String question,
                                        CustomerServiceResult result, Instant createdAt) {
            idempotency.put(key, new IdempotencyRecord(scope, question, result, createdAt));
        }

        @Override
        public List<String> evict(Instant now) {
            return List.of();
        }

        @Override
        public int count(Instant now) {
            return sessions.size();
        }
    }

    private static final class RecordingTicketPort implements CustomerTicketPort {
        private final Map<String, CustomerTicket> tickets = new HashMap<>();
        private final List<String> events;
        private boolean created;
        private boolean listed;
        private boolean updated;

        private RecordingTicketPort(List<String> events) {
            this.events = events;
        }

        @Override
        public CustomerTicket create(String sessionId, String intent, String safeSummary, Instant createdAt) {
            created = true;
            events.add("ticket.create");
            CustomerTicket ticket = new CustomerTicket(
                    "CS-PORT-001", sessionId, intent, CustomerTicketStatus.WAITING_AGENT.name(), safeSummary, createdAt);
            tickets.put(ticket.id(), ticket);
            return ticket;
        }

        @Override
        public List<CustomerTicket> list() {
            listed = true;
            events.add("ticket.list");
            return List.copyOf(tickets.values());
        }

        @Override
        public CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus) {
            updated = true;
            events.add("ticket.update");
            CustomerTicket updatedTicket = tickets.get(ticketId).transitionTo(nextStatus);
            tickets.put(ticketId, updatedTicket);
            return updatedTicket;
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            tickets.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
        }
    }
}
