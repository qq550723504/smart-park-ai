package com.example.smartpark.voice.model;

import java.util.List;
import java.util.Objects;

/**
 * The validated answer handed to TTS. evidenceRefs are traceable references
 * (document ids, looked-up entity ids) produced by this turn's tool calls.
 */
public record VoiceAnswer(String text, List<String> evidenceRefs, List<ToolCallRecord> toolCalls) {

    public VoiceAnswer {
        text = text == null ? "" : text.trim();
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    }
}
