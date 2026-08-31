package com.example.smartpark.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertTriageAgentTest {

    @Test
    void triageRequestUsesStrictProviderSchemaWithNumericConfidence() {
        TestChatModel model = new TestChatModel("""
                {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":0.92}
                """);

        new AlertTriageAgent(model).classify(sampleAlert());

        assertThat(model.lastPrompt().getOptions()).isInstanceOf(DashScopeChatOptions.class);
        DashScopeResponseFormat responseFormat = ((DashScopeChatOptions) model.lastPrompt().getOptions())
                .getResponseFormat();
        assertThat(responseFormat.getType()).isEqualTo(DashScopeResponseFormat.Type.JSON_SCHEMA);
        assertThat(responseFormat.getJsonScheme().getStrict()).isTrue();
        assertThat(responseFormat.getJsonScheme().getName()).isEqualTo("alert_triage");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) responseFormat.getJsonScheme().getSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder(
                "category", "priority", "riskLevel", "confidence");
        assertThat(properties.get("confidence")).asString().contains("type=number");
    }

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
        assertThat(model.lastPrompt().getSystemMessage().getText())
                .contains("TEMPERATURE | POWER | ENERGY | ACCESS | PUMP | UNKNOWN")
                .contains("LOW | HIGH");
        assertThat(model.lastPrompt().getUserMessage().getText()).contains("ALT-TEMP-001");
    }

    @Test
    void malformedModelOutputDoesNotBecomeAFalseDiagnosis() {
        TestChatModel model = new TestChatModel("not-json", "not-json");

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triage");
    }

    @Test
    void retriesOnceWhenModelReturnsInvalidJsonThenAcceptsTheCorrectedResponse() {
        TestChatModel model = new TestChatModel(
                "not-json",
                "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":0.92}");

        AlertTriageAgent.AlertClassificationResult result = new AlertTriageAgent(model).classify(sampleAlert());

        assertThat(result.category()).isEqualTo(AlertClassification.TEMPERATURE);
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(model.lastPrompt().getSystemMessage().getText())
                .contains("exactly one JSON object")
                .contains("Markdown fences");
    }

    @Test
    void rejectsJsonWrappedInMarkdownAfterOneBoundedRetry() {
        String content = """
                ```json
                {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":0.92}
                ```
                """;
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(ModelOutputException.class)
                .hasMessage("triage structured output was invalid")
                .hasNoCause();
        assertThat(model.callCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"category\":\"BOGUS\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":0.92}",
            "{\"category\":\"TEMPERATURE\",\"priority\":\"URGENT\",\"riskLevel\":\"LOW\",\"confidence\":0.92}",
            "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"SEVERE\",\"confidence\":0.92}"
    })
    void invalidTriageEnumsFailClosed(String content) {
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outOfRangeConfidenceFailsClosed() {
        String content = """
                {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":1.5}
                """;
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("strictlyInvalidTriageOutputs")
    void triageStrictReaderRejectsMissingNullUnknownAndQuotedConfidence(String content) {
        TestChatModel model = new TestChatModel(content, content);

        assertThatThrownBy(() -> new AlertTriageAgent(model).classify(sampleAlert()))
                .isInstanceOf(ModelOutputException.class)
                .hasMessage("triage structured output was invalid")
                .hasNoCause();
        assertThat(model.callCount()).isEqualTo(2);
    }

    private static Stream<String> strictlyInvalidTriageOutputs() {
        return Stream.of(
                "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\"}",
                "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":null}",
                "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":0.92,\"unexpected\":true}",
                "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":\"0.92\"}");
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
