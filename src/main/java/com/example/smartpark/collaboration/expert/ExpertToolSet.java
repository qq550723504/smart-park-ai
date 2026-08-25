package com.example.smartpark.collaboration.expert;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public record ExpertToolSet(List<Object> tools, ToolCallback[] callbacks) {
    public ExpertToolSet {
        tools = List.copyOf(tools);
        callbacks = callbacks.clone();
    }

    public static ExpertToolSet of(Object... tools) {
        return new ExpertToolSet(List.of(tools), ToolCallbacks.from(tools));
    }
}
