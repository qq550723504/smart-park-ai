package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityEventReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSecurityAdapterTest {

    @Test
    void listsSeededSecurityEventsAsAnImmutableProjection() {
        SecurityEventReader port = new MockSecurityAdapter(new MockParkDataStore());

        List<SecurityEvent> events = port.listEvents();

        assertThat(events).extracting(SecurityEvent::eventId).containsExactly("SEC-ACCESS-001");
        assertThatThrownBy(() -> events.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
