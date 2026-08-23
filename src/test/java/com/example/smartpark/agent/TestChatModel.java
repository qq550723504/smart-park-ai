package com.example.smartpark.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

public final class TestChatModel implements ChatModel {

    private final Deque<ChatResponse> responses;
    private int calls;
    private Prompt lastPrompt;

    public TestChatModel(String... contents) {
        this.responses = new ArrayDeque<>();
        Arrays.stream(contents)
                .map(TestChatModel::responseOf)
                .forEach(this.responses::addLast);
    }

    public Prompt lastPrompt() {
        return lastPrompt;
    }

    public int callCount() {
        return calls;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        lastPrompt = Objects.requireNonNull(prompt, "prompt");
        calls++;
        ChatResponse response = responses.pollFirst();
        if (response == null) {
            throw new IllegalStateException("TestChatModel received more calls than configured");
        }
        return response;
    }

    private static ChatResponse responseOf(String content) {
        return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(content))));
    }
}
