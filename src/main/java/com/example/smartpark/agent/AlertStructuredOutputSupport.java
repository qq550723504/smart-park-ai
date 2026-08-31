package com.example.smartpark.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;

final class AlertStructuredOutputSupport {

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

    static <T> T convert(BeanOutputConverter<T> converter, String text, String context) {
        if (text == null || text.isBlank()) {
            throw new ModelOutputException(context + " response text was blank");
        }
        try {
            T converted = converter.convert(text);
            if (converted == null) {
                throw new ModelOutputException(context + " structured output was empty");
            }
            return converted;
        }
        catch (ModelOutputException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new ModelOutputException(context + " structured output was invalid", exception);
        }
    }
}
