package com.example.smartpark.voice;

import com.example.smartpark.voice.model.AudioChunkFrame;
import com.example.smartpark.voice.model.AnswerDeltaFrame;
import com.example.smartpark.voice.model.AsrFinalFrame;
import com.example.smartpark.voice.model.AsrPartialFrame;
import com.example.smartpark.voice.model.ErrorFrame;
import com.example.smartpark.voice.model.SessionStateFrame;
import com.example.smartpark.voice.model.ToolEventFrame;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.model.VoiceServerFrame;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Recording publisher capturing typed frames and raw binary audio chunks. */
final class VoiceFrameRecorder implements VoiceFramePublisher {

    final List<VoiceServerFrame> frames = new CopyOnWriteArrayList<>();
    record Binary(int sequence, byte[] pcm) {
    }
    final List<Binary> binaries = new CopyOnWriteArrayList<>();

    @Override
    public void publish(VoiceServerFrame frame) {
        frames.add(frame);
    }

    @Override
    public void publishAudioChunk(int chunkSequence, byte[] pcm) {
        binaries.add(new Binary(chunkSequence, pcm.clone()));
    }

    List<SessionStateFrame> states() {
        return frames.stream().filter(SessionStateFrame.class::isInstance)
                .map(SessionStateFrame.class::cast).toList();
    }

    List<AsrPartialFrame> partials() {
        return frames.stream().filter(AsrPartialFrame.class::isInstance)
                .map(AsrPartialFrame.class::cast).toList();
    }

    List<String> deltas() {
        return frames.stream().filter(AnswerDeltaFrame.class::isInstance)
                .map(AnswerDeltaFrame.class::cast).map(AnswerDeltaFrame::delta).toList();
    }

    List<AudioChunkFrame> chunkAnnouncements() {
        return frames.stream().filter(AudioChunkFrame.class::isInstance)
                .map(AudioChunkFrame.class::cast).toList();
    }

    List<ToolEventFrame> toolEvents() {
        return frames.stream().filter(ToolEventFrame.class::isInstance)
                .map(ToolEventFrame.class::cast).toList();
    }

    ErrorFrame lastError() {
        return frames.stream().filter(ErrorFrame.class::isInstance)
                .map(ErrorFrame.class::cast).reduce((first, second) -> second).orElse(null);
    }

    SessionStateFrame lastState() {
        return states().isEmpty() ? null : states().get(states().size() - 1);
    }

    boolean sawState(com.example.smartpark.voice.model.VoiceSessionState state) {
        return states().stream().anyMatch(frame -> frame.state() == state);
    }

    VoiceErrorCode lastErrorCode() {
        ErrorFrame frame = lastError();
        return frame == null ? null : frame.code();
    }

    String lastErrorMessage() {
        ErrorFrame frame = lastError();
        return frame == null ? null : frame.userMessage();
    }
}
