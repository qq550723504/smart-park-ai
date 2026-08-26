package com.example.smartpark.voice;

import com.example.smartpark.voice.model.ClientControlFrame;
import com.example.smartpark.voice.model.SessionStateFrame;
import com.example.smartpark.voice.model.VoiceClientControlType;
import com.example.smartpark.voice.model.VoiceEnvelope;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.model.VoiceServerFrameType;
import com.example.smartpark.voice.model.VoiceSessionEvent;
import com.example.smartpark.voice.model.VoiceSessionState;
import com.example.smartpark.voice.model.VoiceSessionStateMachine;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceSessionStateMachineTest {

    private VoiceSessionStateMachine newMachine() {
        return new VoiceSessionStateMachine();
    }

    @Test
    void startsIdleAndFollowsHappyPathThroughSpeakingBackToIdle() {
        VoiceSessionStateMachine machine = newMachine();

        assertThat(machine.state()).isEqualTo(VoiceSessionState.IDLE);

        machine.apply(VoiceSessionEvent.START_INPUT);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.LISTENING);

        machine.apply(VoiceSessionEvent.INPUT_COMMITTED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.ASR_FINALIZED);

        machine.apply(VoiceSessionEvent.REASONING_STARTED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.REASONING);

        machine.apply(VoiceSessionEvent.ANSWER_STREAM_STARTED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.ANSWER_STREAMING);

        machine.apply(VoiceSessionEvent.ANSWER_COMPLETED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.SPEAKING);

        machine.apply(VoiceSessionEvent.TURN_COMPLETED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.IDLE);
    }

    @Test
    void reasoningAndToolCallingMayLoopWithinOneTurn() {
        VoiceSessionStateMachine machine = newMachine();
        machine.apply(VoiceSessionEvent.START_INPUT);
        machine.apply(VoiceSessionEvent.INPUT_COMMITTED);
        machine.apply(VoiceSessionEvent.REASONING_STARTED);

        machine.apply(VoiceSessionEvent.TOOL_CALL_STARTED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.TOOL_CALLING);

        machine.apply(VoiceSessionEvent.TOOL_CALL_COMPLETED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.REASONING);

        machine.apply(VoiceSessionEvent.TOOL_CALL_STARTED);
        machine.apply(VoiceSessionEvent.TOOL_CALL_COMPLETED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.REASONING);

        machine.apply(VoiceSessionEvent.ANSWER_STREAM_STARTED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.ANSWER_STREAMING);
    }

    @Test
    void startInputDuringSpeakingInterruptsThenReturnsToListening() {
        VoiceSessionStateMachine machine = newMachine();
        machine.apply(VoiceSessionEvent.START_INPUT);
        machine.apply(VoiceSessionEvent.INPUT_COMMITTED);
        machine.apply(VoiceSessionEvent.REASONING_STARTED);
        machine.apply(VoiceSessionEvent.ANSWER_STREAM_STARTED);
        machine.apply(VoiceSessionEvent.ANSWER_COMPLETED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.SPEAKING);

        VoiceSessionStateMachine.Transition outcome =
                machine.apply(VoiceSessionEvent.START_INPUT);

        assertThat(outcome.previous()).isEqualTo(VoiceSessionState.SPEAKING);
        assertThat(outcome.current()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(outcome.interruptedOutput()).isTrue();
    }

    @Test
    void startInputFromErrorRecoversToListeningWithoutInterruptFlag() {
        VoiceSessionStateMachine machine = newMachine();
        machine.apply(VoiceSessionEvent.ERROR_OCCURRED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.ERROR);

        VoiceSessionStateMachine.Transition outcome =
                machine.apply(VoiceSessionEvent.START_INPUT);

        assertThat(outcome.current()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(outcome.interruptedOutput()).isFalse();
    }

    @Test
    void outputCanBeInterruptedFromAnyActiveStageAndLandsIdle() {
        for (VoiceSessionEvent[] path : new VoiceSessionEvent[][]{
                {VoiceSessionEvent.START_INPUT, VoiceSessionEvent.INPUT_COMMITTED,
                        VoiceSessionEvent.REASONING_STARTED},
                {VoiceSessionEvent.START_INPUT, VoiceSessionEvent.INPUT_COMMITTED,
                        VoiceSessionEvent.REASONING_STARTED, VoiceSessionEvent.TOOL_CALL_STARTED},
                {VoiceSessionEvent.START_INPUT, VoiceSessionEvent.INPUT_COMMITTED,
                        VoiceSessionEvent.REASONING_STARTED, VoiceSessionEvent.ANSWER_STREAM_STARTED}}) {
            VoiceSessionStateMachine machine = newMachine();
            for (VoiceSessionEvent event : path) {
                machine.apply(event);
            }
            VoiceSessionStateMachine.Transition outcome =
                    machine.apply(VoiceSessionEvent.OUTPUT_INTERRUPTED);
            assertThat(outcome.current()).isEqualTo(VoiceSessionState.IDLE);
        }
    }

    @Test
    void rejectsOutOfOrderControlsAndBinaryAudioOutsideListening() {
        VoiceSessionStateMachine machine = newMachine();

        // No input started yet: commit and interrupt are out of order.
        assertThat(machine.canApply(VoiceSessionEvent.INPUT_COMMITTED)).isFalse();
        assertThat(machine.canApply(VoiceSessionEvent.OUTPUT_INTERRUPTED)).isFalse();
        assertThatThrownBy(() -> machine.apply(VoiceSessionEvent.INPUT_COMMITTED))
                .isInstanceOf(IllegalStateException.class);

        machine.apply(VoiceSessionEvent.START_INPUT);
        // Duplicate start while already listening is invalid.
        assertThat(machine.canApply(VoiceSessionEvent.START_INPUT)).isFalse();

        // Binary audio is only meaningful while listening; other stages refuse it.
        assertThat(machine.acceptsAudio()).isTrue();
        machine.apply(VoiceSessionEvent.INPUT_COMMITTED);
        assertThat(machine.acceptsAudio()).isFalse();
    }

    @Test
    void errorIsReachableFromActiveStagesButNotFromClosed() {
        VoiceSessionStateMachine machine = newMachine();
        machine.apply(VoiceSessionEvent.START_INPUT);
        machine.apply(VoiceSessionEvent.ERROR_OCCURRED);
        assertThat(machine.state()).isEqualTo(VoiceSessionState.ERROR);

        VoiceSessionStateMachine closedMachine = newMachine();
        closedMachine.apply(VoiceSessionEvent.SESSION_CLOSED);
        assertThat(closedMachine.state()).isEqualTo(VoiceSessionState.CLOSED);
        assertThat(closedMachine.canApply(VoiceSessionEvent.ERROR_OCCURRED)).isFalse();
        assertThat(closedMachine.canApply(VoiceSessionEvent.SESSION_CLOSED)).isFalse();
        assertThatThrownBy(() -> closedMachine.apply(VoiceSessionEvent.START_INPUT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closedIsTerminalAndCannotBeRecovered() {
        for (VoiceSessionEvent event : VoiceSessionEvent.values()) {
            if (event == VoiceSessionEvent.SESSION_CLOSED) {
                continue;
            }
            VoiceSessionStateMachine machine = newMachine();
            machine.apply(VoiceSessionEvent.SESSION_CLOSED);
            assertThat(machine.canApply(event))
                    .as("CLOSED must reject %s", event).isFalse();
        }
    }

    @Test
    void protocolDefinesExactlyTheAgreedFrameVocabulary() {
        assertThat(Set.of(VoiceClientControlType.values())).containsExactlyInAnyOrder(
                VoiceClientControlType.START_INPUT,
                VoiceClientControlType.COMMIT_INPUT,
                VoiceClientControlType.INTERRUPT_OUTPUT,
                VoiceClientControlType.CLOSE_SESSION);

        assertThat(Set.of(VoiceServerFrameType.values())).containsExactlyInAnyOrder(
                VoiceServerFrameType.SESSION_STATE,
                VoiceServerFrameType.ASR_PARTIAL,
                VoiceServerFrameType.ASR_FINAL,
                VoiceServerFrameType.TOOL_EVENT,
                VoiceServerFrameType.ANSWER_DELTA,
                VoiceServerFrameType.AUDIO_CHUNK,
                VoiceServerFrameType.ERROR);
    }

    @Test
    void everyJsonFrameCarriesSessionMessageAndSequenceIdentity() {
        ClientControlFrame control = new ClientControlFrame(
                VoiceClientControlType.START_INPUT, "s-1", "m-1", 0);
        assertThat(control.sessionId()).isEqualTo("s-1");
        assertThat(control.messageId()).isEqualTo("m-1");
        assertThat(control.sequence()).isZero();

        assertThatThrownBy(() -> new ClientControlFrame(
                VoiceClientControlType.START_INPUT, " ", "m-2", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientControlFrame(
                VoiceClientControlType.START_INPUT, "s-1", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientControlFrame(
                VoiceClientControlType.START_INPUT, "s-1", "m-3", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serverFrameTypesExposeSafeErrorCodesWithoutRawProviderDetail() {
        assertThat(Set.of(VoiceErrorCode.values())).containsExactlyInAnyOrder(
                VoiceErrorCode.INVALID_FRAME,
                VoiceErrorCode.UNSUPPORTED_STATE,
                VoiceErrorCode.AUDIO_REJECTED,
                VoiceErrorCode.PROVIDER_FAILURE,
                VoiceErrorCode.TIMEOUT,
                VoiceErrorCode.ANSWER_VALIDATION_FAILED,
                VoiceErrorCode.INTERNAL_ERROR);
    }

    @Test
    void envelopeIsSharedIdentityContractForAllFrames() {
        VoiceEnvelope envelope = new VoiceEnvelope("s-9", "m-9", 42);
        assertThat(envelope.sessionId()).isEqualTo("s-9");
        assertThat(envelope.messageId()).isEqualTo("m-9");
        assertThat(envelope.sequence()).isEqualTo(42L);

        assertThatThrownBy(() -> new VoiceEnvelope(null, "m-9", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceEnvelope("s-9", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceEnvelope("s-9", "m-9", -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionStateFrameExposesOnlyDisplaySafeFields() {
        SessionStateFrame frame = new SessionStateFrame(
                "s-1", "m-1", 3, VoiceSessionState.LISTENING, "t-1");
        assertThat(frame.type()).isEqualTo(VoiceServerFrameType.SESSION_STATE);
        assertThat(frame.state()).isEqualTo(VoiceSessionState.LISTENING);
        assertThat(frame.turnId()).isEqualTo("t-1");
    }
}
