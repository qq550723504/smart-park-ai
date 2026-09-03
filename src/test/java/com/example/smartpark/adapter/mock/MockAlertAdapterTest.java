package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.port.alert.AlertPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAlertAdapterTest {

    @Test
    void listsActiveAlertsAsAnImmutableProjection() {
        AlertPort port = new MockAlertAdapter(new MockParkDataStore());

        List<Alert> alerts = port.listActive();

        assertThat(alerts).extracting(Alert::id).contains("ALT-ACCESS-001");
        assertThatThrownBy(() -> alerts.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
