package com.example.smartpark.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.ai.converter.BeanOutputConverter;

final class AlertStructuredOutputSupport {

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
            throw invalidOutput(context);
        }
        try {
            T converted = reader.readValue(text);
            if (converted == null) {
                throw invalidOutput(context);
            }
            return converted;
        }
        catch (ModelOutputException exception) {
            throw exception;
        }
        catch (JsonProcessingException | RuntimeException exception) {
            throw invalidOutput(context);
        }
    }

    private static ModelOutputException invalidOutput(String context) {
        return new ModelOutputException(context + " structured output was invalid");
    }
}
