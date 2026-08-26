package com.example.smartpark.voice;

import com.example.smartpark.adapter.mock.MockParkFixture;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.voice.port.FakeStreamingAsrPort;
import com.example.smartpark.voice.port.FakeStreamingTtsPort;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared deterministic harness for voice session-layer tests. */
public final class VoiceTestSupport {

    final MockParkFixture fixture = new MockParkFixture();
    public final FakeStreamingAsrPort asrPort = new FakeStreamingAsrPort();
    public final FakeStreamingTtsPort ttsPort = new FakeStreamingTtsPort();
    public final ManualDeadlines deadlines = new ManualDeadlines();
    public final RecordingEventPublisher events = new RecordingEventPublisher();

    public final VoiceSessionStore store = new VoiceSessionStore();
    public final VoiceSessionService service;
    public final VoiceFrameRecorder frames = new VoiceFrameRecorder();

    public VoiceTestSupport(String classifyJson, List<String> streamedChunks) {
        var chatModel = new com.example.smartpark.voice.VoiceAnswerAgentTest.ScriptedChatModel(
                classifyJson, streamedChunks);
        var agent = new VoiceAnswerAgent(
                chatModel,
                new AlertQueryTool(fixture.alerts()),
                new EnergyQueryTool(fixture.energy()),
                new ParkKnowledgeTool(fixture.knowledge()),
                new com.example.smartpark.voice.VoiceAnswerValidator());
        this.service = new VoiceSessionService(
                store, asrPort, ttsPort, agent, events,
                VoiceDeadlines.defaults(), deadlines, Runnable::run);
    }

    public record Created(VoiceSession session, VoiceSessionService.CreatedSession info) {
    }

    public Created createSession() {
        var created = service.createSession(frames);
        return new Created(store.find(created.sessionId()).orElseThrow(), created);
    }

    public void flush(VoiceSession session) {
        session.flush();
    }

    public static final class ManualDeadlines implements DeadlineScheduler {
        record Pending(Runnable task, Duration delay) {
        }

        public final List<Pending> pending = new CopyOnWriteArrayList<>();

        @Override
        public Cancelable schedule(Runnable task, Duration delay) {
            Pending entry = new Pending(task, delay);
            pending.add(entry);
            return () -> pending.remove(entry);
        }

        void triggerAll() {
            List<Pending> snapshot = List.copyOf(pending);
            pending.clear();
            snapshot.forEach(entry -> entry.task().run());
        }
    }

    public static final class RecordingEventPublisher implements ExecutionEventPublisher {
        public final List<ExecutionEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public ExecutionEvent publish(ExecutionEvent event) {
            published.add(event);
            return event;
        }

        @Override
        public List<ExecutionEvent> history(UUID runId) {
            return List.copyOf(published);
        }

        @Override
        public Subscription subscribe(UUID runId, java.util.function.Consumer<ExecutionEvent> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String status(UUID runId) {
            return "COMPLETED";
        }

        @Override
        public void remove(UUID runId) {
        }

        public boolean sawTerminalType(ExecutionEventType type) {
            return published.stream().anyMatch(event -> event.eventType() == type);
        }
    }
}
