package com.example.smartpark.voice;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.voice.model.AnswerValidationException;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceAnswerAgentTest {

    private final MockParkFixture fixture = new MockParkFixture();

    private VoiceAnswerAgent newAgent(ScriptedChatModel chatModel, String classifyJson, List<String> streamedChunks) {
        return new VoiceAnswerAgent(
                chatModel,
                new AlertQueryTool(fixture.alerts()),
                new EnergyQueryTool(fixture.energy()),
                new ParkKnowledgeTool(fixture.knowledge()),
                new VoiceAnswerValidator());
    }

    private VoiceAnswerAgent newAgent(String classifyJson, List<String> streamedChunks) {
        return newAgent(new ScriptedChatModel(classifyJson, streamedChunks), classifyJson, streamedChunks);
    }

    @Test
    void alertQuestionRoutesToLookupAlertAndStreamsGroundedAnswer() {
        RecordingListener listener = new RecordingListener();
        ScriptedChatModel chatModel = new ScriptedChatModel(
                "{\"intent\":\"ALERT\",\"alertId\":\"ALT-TEMP-001\"}",
                List.of("告警 ", "ALT-TEMP-001 已确认：", "DEV-HVAC-001 温度上升，风险等级 LOW。"));
        VoiceAnswerAgent agent = newAgent(chatModel,
                "{\"intent\":\"ALERT\",\"alertId\":\"ALT-TEMP-001\"}", chatModel.chunks);

        VoiceAnswer answer = agent.answer("s-1", "t-1", "空调机房那条告警怎么回事？", listener);

        assertThat(answer.text()).contains("ALT-TEMP-001").contains("DEV-HVAC-001");
        assertThat(answer.toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.toolName()).isEqualTo("lookupAlert"));
        assertThat(listener.startedTools).containsExactly("lookupAlert");
        assertThat(listener.completedTools).containsExactly("lookupAlert");
        // 文本 delta 由后端真实流产生并逐段发布。
        assertThat(listener.deltas).isNotEmpty();
        assertThat(String.join("", listener.deltas)).isEqualTo(answer.text());
        // grounding prompt 携带本 turn 工具证据。
        assertThat(chatModel.streamedPromptText())
                .contains("ALT-TEMP-001").contains("DEV-HVAC-001");
    }

    @Test
    void energyQuestionReturnsMeterNumbersBackedByToolEvidence() {
        RecordingListener listener = new RecordingListener();
        VoiceAnswerAgent agent = newAgent(
                "{\"intent\":\"ENERGY\",\"meterId\":\"DEV-ENERGY-001\"}",
                List.of("A2 表计当前用电 138 千瓦时，高于基线 100 千瓦时。"));

        VoiceAnswer answer = agent.answer("s-1", "t-1", "A2 楼现在用了多少电？", listener);

        assertThat(answer.text()).contains("138").contains("100");
        assertThat(answer.evidenceRefs()).contains("DEV-ENERGY-001");
        assertThat(listener.completedTools).containsExactly("lookupEnergyConsumption");
    }

    @Test
    void parkingPolicyQuestionCitesKnowledgeDocument() {
        RecordingListener listener = new RecordingListener();
        VoiceAnswerAgent agent = newAgent(
                "{\"intent\":\"PARKING_POLICY\",\"keyword\":\"停车\"}",
                List.of("访客车辆请先完成入场登记后再使用访客停车场。[doc:KD-PARKING-001]"));

        VoiceAnswer answer = agent.answer("s-1", "t-1", "访客怎么停车？", listener);

        assertThat(answer.evidenceRefs()).contains("KD-PARKING-001");
        assertThat(answer.toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.toolName()).isEqualTo("searchVisitorGuide"));
    }

    @Test
    void dataClaimWithoutAnyToolCallIsRejectedExplicitly() {
        RecordingListener listener = new RecordingListener();
        VoiceAnswerAgent agent = newAgent(
                "{\"intent\":\"CHITCHAT\"}",
                List.of("园区今天用电 138 千瓦时。"));

        assertThatThrownBy(() -> agent.answer("s-1", "t-1", "园区今天怎么样？", listener))
                .isInstanceOf(AnswerValidationException.class);
    }

    @Test
    void citationOutsideThisTurnKnowledgeIsRejected() {
        RecordingListener listener = new RecordingListener();
        VoiceAnswerAgent agent = newAgent(
                "{\"intent\":\"PARKING_POLICY\",\"keyword\":\"停车\"}",
                List.of("访客停车免费。[doc:KD-FAKE-999]"));

        assertThatThrownBy(() -> agent.answer("s-1", "t-1", "访客怎么停车？", listener))
                .isInstanceOf(AnswerValidationException.class);
    }

    @Test
    void writeRequestGetsExplicitRefusalWithoutAnyToolOrGenerationCall() {
        RecordingListener listener = new RecordingListener();
        ScriptedChatModel chatModel = new ScriptedChatModel("{\"intent\":\"WRITE_REQUEST\"}", List.of());
        VoiceAnswerAgent agent = new VoiceAnswerAgent(
                chatModel,
                new AlertQueryTool(fixture.alerts()),
                new EnergyQueryTool(fixture.energy()),
                new ParkKnowledgeTool(fixture.knowledge()),
                new VoiceAnswerValidator());

        VoiceAnswer answer = agent.answer("s-1", "t-1", "帮我把 A 区大门打开", listener);

        assertThat(answer.text()).contains("只读").doesNotContain("已执行").doesNotContain("好的");
        assertThat(answer.toolCalls()).isEmpty();
        assertThat(listener.startedTools).isEmpty();
        assertThat(chatModel.streamInvocations).isZero();
    }

    @Test
    void alertIntentWithoutIdAsksForIdentifierInsteadOfGuessing() {
        RecordingListener listener = new RecordingListener();
        VoiceAnswerAgent agent = newAgent("{\"intent\":\"ALERT\"}", List.of());

        VoiceAnswer answer = agent.answer("s-1", "t-1", "有告警吗？", listener);

        assertThat(answer.text()).contains("告警编号");
        assertThat(answer.toolCalls()).isEmpty();
        assertThat(listener.startedTools).isEmpty();
    }


    /** Deterministic ChatModel fake: call() returns the scripted classification JSON, stream() replays chunks. */
    static final class ScriptedChatModel implements ChatModel {
        final String classifyJson;
        final List<String> chunks;
        private final List<String> streamedPrompts = new CopyOnWriteArrayList<>();
        int streamInvocations;

        ScriptedChatModel(String classifyJson, List<String> chunks) {
            this.classifyJson = classifyJson;
            this.chunks = chunks;
        }

        String streamedPromptText() {
            return String.join("|", streamedPrompts);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(classifyJson))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamInvocations++;
            prompt.getInstructions().forEach(message -> streamedPrompts.add(message.getText()));
            return Flux.fromIterable(chunks)
                    .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
        }
    }

    private static final class RecordingListener implements VoiceAnswerAgent.Listener {
        final List<String> startedTools = new ArrayList<>();
        final List<String> completedTools = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();

        @Override
        public void onToolStarted(String toolName, String argumentSummary) {
            startedTools.add(toolName);
        }

        @Override
        public void onToolCompleted(String toolName, boolean success) {
            completedTools.add(toolName);
        }

        @Override
        public void onTextDelta(String delta) {
            deltas.add(delta);
        }
    }
}
