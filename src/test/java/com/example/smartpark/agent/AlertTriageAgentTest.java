package com.example.smartpark.agent;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertTriageAgentTest {

    @Test
    void triageConvertsTheModelResponseIntoAlertClassification() {
        TestChatModel model = new TestChatModel("""
                {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":0.92}
                """);

        AlertTriageAgent.AlertClassificationResult result = new AlertTriageAgent(model).classify(sampleAlert());

        assertThat(result.category()).isEqualTo(AlertClassification.TEMPERATURE);
        assertThat(result.priority()).isEqualTo(AlertTriageAgent.AlertPriority.MEDIUM);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.confidence()).isEqualTo(0.92);
        assertThat(model.lastPrompt().getSystemMessage().getText()).contains("JSON");
        assertThat(model.lastPrompt().getUserMessage().getText()).contains("ALT-TEMP-001");
    }

    @Test
    void malformedModelOutputDoesNotBecomeAFalseDiagnosis() {
        TestChatModel model = new TestChatModel("not-json");

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triage");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"category\":\"BOGUS\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":0.92}",
            "{\"category\":\"TEMPERATURE\",\"priority\":\"URGENT\",\"riskLevel\":\"LOW\",\"confidence\":0.92}",
            "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"SEVERE\",\"confidence\":0.92}"
    })
    void invalidTriageEnumsFailClosed(String content) {
        TestChatModel model = new TestChatModel(content);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outOfRangeConfidenceFailsClosed() {
        TestChatModel model = new TestChatModel("""
                {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":1.5}
                """);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confidence");
    }

    private static Alert sampleAlert() {
        return new Alert(
                "ALT-TEMP-001",
                "PARK-A",
                "A1",
                "DEV-HVAC-001",
                AlertClassification.TEMPERATURE,
                RiskLevel.LOW,
                "Temperature rising in HVAC room",
                Instant.parse("2026-08-23T00:15:00Z"),
                List.of("sensor:temp-01", "trend:upward"));
    }
}
