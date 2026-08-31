package com.example.smartpark.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AlertStructuredOutputSupportTest {

    private static final String SENSITIVE_PAYLOAD = String.join("_", List.of(
            "RAW_RESPONSE_SENTINEL",
            "MODEL_VALUE_SENTINEL",
            "PROMPT_SENTINEL",
            "EVIDENCE_SENTINEL",
            "ALERT_ID_SENTINEL",
            "CREDENTIAL_SENTINEL"));

    @Test
    void malformedProviderTextLogsOnlyBoundedRejectionMetadata() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(TestOutput.class),
                "{\"confidence\":" + SENSITIVE_PAYLOAD,
                "triage",
                "MALFORMED_JSON",
                "UNKNOWN");
    }

    @Test
    void unknownFieldsLogUnknownInsteadOfTheUntrustedPropertyName() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(TestOutput.class),
                "{\"confidence\":0.5,\"unexpected_" + SENSITIVE_PAYLOAD + "\":0.5}",
                "diagnosis",
                "UNKNOWN_FIELD",
                "UNKNOWN");
    }

    @Test
    void scalarTypeMismatchesLogTheAllowedFieldName() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(TestOutput.class),
                "{\"confidence\":\"" + SENSITIVE_PAYLOAD + "\"}",
                "triage",
                "TYPE_MISMATCH",
                "confidence");
    }

    @Test
    void invalidEnumValuesLogTheAllowedFieldName() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(AlertTriageAgent.AlertClassificationResult.class),
                """
                        {"category":"%s","priority":"LOW","riskLevel":"LOW","confidence":0.5}
                        """.formatted(SENSITIVE_PAYLOAD),
                "triage",
                "INVALID_VALUE",
                "category");
    }

    @Test
    void outputConstraintsLogUnknownWhenJacksonCannotIdentifyTheField() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(AlertTriageAgent.AlertClassificationResult.class),
                "{\"category\":\"TEMPERATURE\",\"priority\":\"LOW\",\"riskLevel\":\"LOW\",\"confidence\":1.5}",
                "triage",
                "CONSTRAINT_VIOLATION",
                "UNKNOWN");
    }

    @Test
    void unrecognizedContextsAreBoundedInLogs() {
        assertRejectedLogs(
                AlertStructuredOutputSupport.reader(TestOutput.class),
                "{\"confidence\":" + SENSITIVE_PAYLOAD,
                "UNTRUSTED_CONTEXT_SENTINEL",
                "MALFORMED_JSON",
                "UNKNOWN");
    }

    private static void assertRejectedLogs(
            com.fasterxml.jackson.databind.ObjectReader reader,
            String text,
            String context,
            String rejection,
            String field) {
        Logger logger = (Logger) LoggerFactory.getLogger(AlertStructuredOutputSupport.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Throwable failure = catchThrowable(() -> AlertStructuredOutputSupport.convert(
                    reader,
                    text,
                    context));

            assertThat(failure)
                    .isInstanceOf(ModelOutputException.class)
                    .hasMessage(context + " structured output was invalid")
                    .hasNoCause();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("context=" + expectedContext(context)
                                + " rejection=" + rejection + " field=" + field)
                        .doesNotContain(SENSITIVE_PAYLOAD, "UNTRUSTED_CONTEXT_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static String expectedContext(String context) {
        return switch (context) {
            case "triage" -> "TRIAGE";
            case "diagnosis" -> "DIAGNOSIS";
            default -> "UNKNOWN";
        };
    }

    private record TestOutput(double confidence) {
    }
}
