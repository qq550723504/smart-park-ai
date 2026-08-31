package com.example.smartpark.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.ai.converter.BeanOutputConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AlertStructuredOutputSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertStructuredOutputSupport.class);

    private static final JsonMapper STRICT_MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .enable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AlertStructuredOutputSupport() {
    }

    static <T> BeanOutputConverter<T> converter(Class<T> outputType) {
        return new BeanOutputConverter<>(outputType);
    }

    static DashScopeChatOptions providerOptions(String schemaName, BeanOutputConverter<?> converter) {
        DashScopeResponseFormat.JsonSchemaConfig schema = DashScopeResponseFormat.JsonSchemaConfig.builder()
                .name(schemaName)
                .description("Strict structured output for the smart-park alert workflow")
                .schema(converter.getJsonSchemaMap())
                .strict(true)
                .build();
        return DashScopeChatOptions.builder()
                .responseFormat(DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_SCHEMA)
                        .jsonScheme(schema)
                        .build())
                .build();
    }

    static ObjectReader reader(Class<?> outputType) {
        return STRICT_MAPPER.readerFor(outputType);
    }

    static <T> T convert(ObjectReader reader, String text, String context) {
        if (text == null || text.isBlank()) {
            throw invalidOutput(context, Rejection.MALFORMED_JSON, AlertSchemaField.UNKNOWN);
        }
        try {
            T converted = reader.readValue(text);
            if (converted == null) {
                throw invalidOutput(context, Rejection.CONSTRAINT_VIOLATION, AlertSchemaField.UNKNOWN);
            }
            return converted;
        }
        catch (ModelOutputException exception) {
            throw exception;
        }
        catch (JsonProcessingException exception) {
            throw invalidOutput(context, rejectionFor(exception), fieldFor(exception));
        }
        catch (RuntimeException exception) {
            throw invalidOutput(context, Rejection.CONSTRAINT_VIOLATION, AlertSchemaField.UNKNOWN);
        }
    }

    private static ModelOutputException invalidOutput(
            String context,
            Rejection rejection,
            AlertSchemaField field) {
        LOGGER.warn("context={} rejection={} field={}", AlertContext.from(context), rejection, field);
        return new ModelOutputException(context + " structured output was invalid");
    }

    private static Rejection rejectionFor(JsonProcessingException exception) {
        if (exception instanceof JsonParseException) {
            return Rejection.MALFORMED_JSON;
        }
        if (exception instanceof UnrecognizedPropertyException) {
            return Rejection.UNKNOWN_FIELD;
        }
        if (exception instanceof InvalidFormatException) {
            return Rejection.INVALID_VALUE;
        }
        if (exception instanceof ValueInstantiationException) {
            return Rejection.CONSTRAINT_VIOLATION;
        }
        if (exception instanceof MismatchedInputException) {
            return Rejection.TYPE_MISMATCH;
        }
        return Rejection.CONSTRAINT_VIOLATION;
    }

    private static AlertSchemaField fieldFor(JsonProcessingException exception) {
        if (exception instanceof UnrecognizedPropertyException) {
            return AlertSchemaField.UNKNOWN;
        }
        if (exception instanceof JsonMappingException mappingException
                && !mappingException.getPath().isEmpty()) {
            return AlertSchemaField.from(mappingException.getPath()
                    .get(mappingException.getPath().size() - 1)
                    .getFieldName());
        }
        return AlertSchemaField.UNKNOWN;
    }

    private enum AlertContext {
        TRIAGE,
        DIAGNOSIS,
        UNKNOWN;

        private static AlertContext from(String context) {
            return switch (context) {
                case "triage" -> TRIAGE;
                case "diagnosis" -> DIAGNOSIS;
                default -> UNKNOWN;
            };
        }
    }

    private enum Rejection {
        UNKNOWN_FIELD,
        TYPE_MISMATCH,
        INVALID_VALUE,
        CONSTRAINT_VIOLATION,
        MALFORMED_JSON
    }

    private enum AlertSchemaField {
        CATEGORY("category"),
        PRIORITY("priority"),
        RISK_LEVEL("riskLevel"),
        CONFIDENCE("confidence"),
        ROOT_CAUSE("rootCause"),
        SUMMARY("summary"),
        EVIDENCE("evidence"),
        RECOMMENDED_ACTION("recommendedAction"),
        UNKNOWN("UNKNOWN");

        private final String value;

        AlertSchemaField(String value) {
            this.value = value;
        }

        private static AlertSchemaField from(String fieldName) {
            for (AlertSchemaField field : values()) {
                if (field.value.equals(fieldName)) {
                    return field;
                }
            }
            return UNKNOWN;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
