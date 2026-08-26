package com.example.smartpark.voice.model;

/**
 * Pure in-memory state machine for one voice session turn loop.
 * Contains no networking and no vendor SDK types so it stays unit-testable.
 */
public final class VoiceSessionStateMachine {

    /** Outcome of one accepted transition. */
    public record Transition(VoiceSessionState previous, VoiceSessionState current) {
        public boolean interruptedOutput() {
            return previous == VoiceSessionState.ANSWER_STREAMING
                    || previous == VoiceSessionState.SPEAKING;
        }
    }

    private VoiceSessionState state = VoiceSessionState.IDLE;

    public VoiceSessionStateMachine() {
    }

    public VoiceSessionState state() {
        return state;
    }

    /** Binary audio frames are only meaningful while capturing input. */
    public boolean acceptsAudio() {
        return state == VoiceSessionState.LISTENING;
    }

    public boolean canApply(VoiceSessionEvent event) {
        return targetFor(event).isPresent();
    }

    /**
     * Applies the event and returns the transition outcome.
     * START_INPUT while SPEAKING/ANSWER_STREAMING interrupts current output first,
     * which callers must honor by cancelling TTS before feeding new audio.
     *
     * @throws IllegalStateException when the event is illegal for the current state
     */
    public Transition apply(VoiceSessionEvent event) {
        VoiceSessionState previous = state;
        VoiceSessionState target = targetFor(event)
                .orElseThrow(() -> new IllegalStateException(
                        "Event " + event + " is not allowed in state " + previous));
        state = target;
        return new Transition(previous, target);
    }

    private java.util.Optional<VoiceSessionState> targetFor(VoiceSessionEvent event) {
        return switch (state) {
            case IDLE -> switch (event) {
                case START_INPUT -> java.util.Optional.of(VoiceSessionState.LISTENING);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case LISTENING -> switch (event) {
                case INPUT_COMMITTED -> java.util.Optional.of(VoiceSessionState.ASR_FINALIZED);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case ASR_FINALIZED -> switch (event) {
                case REASONING_STARTED -> java.util.Optional.of(VoiceSessionState.REASONING);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case REASONING -> switch (event) {
                case TOOL_CALL_STARTED -> java.util.Optional.of(VoiceSessionState.TOOL_CALLING);
                case ANSWER_STREAM_STARTED -> java.util.Optional.of(VoiceSessionState.ANSWER_STREAMING);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case TOOL_CALLING -> switch (event) {
                case TOOL_CALL_COMPLETED -> java.util.Optional.of(VoiceSessionState.REASONING);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case ANSWER_STREAMING -> switch (event) {
                case ANSWER_COMPLETED -> java.util.Optional.of(VoiceSessionState.SPEAKING);
                case START_INPUT -> java.util.Optional.of(VoiceSessionState.LISTENING);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case SPEAKING -> switch (event) {
                case TURN_COMPLETED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case START_INPUT -> java.util.Optional.of(VoiceSessionState.LISTENING);
                case OUTPUT_INTERRUPTED -> java.util.Optional.of(VoiceSessionState.IDLE);
                case ERROR_OCCURRED -> java.util.Optional.of(VoiceSessionState.ERROR);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case ERROR -> switch (event) {
                // Retryable: a fresh input attempt recovers to listening.
                case START_INPUT -> java.util.Optional.of(VoiceSessionState.LISTENING);
                case SESSION_CLOSED -> java.util.Optional.of(VoiceSessionState.CLOSED);
                default -> java.util.Optional.empty();
            };
            case CLOSED -> java.util.Optional.empty();
        };
    }
}
