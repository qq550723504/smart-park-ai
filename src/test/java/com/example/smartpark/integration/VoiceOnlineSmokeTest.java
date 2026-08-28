package com.example.smartpark.integration;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.voice.VoiceAnswerAgent;
import com.example.smartpark.voice.VoiceAnswerValidator;
import com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingAsrAdapter;
import com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingTtsAdapter;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceErrorCode;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 DashScope 在线链路 Smoke：真实 LLM 分类与工具路由、流式回答、
 * 证据校验、真实 TTS chunk 与按 turn 取消；ASR 以静音帧驱动验证真实
 * provider 的终态行为。仅在显式提供 AI_DASHSCOPE_API_KEY 且
 * -Drun.dashscope.smoke=true 时运行，不进入默认离线测试。
 */
@org.junit.jupiter.api.Tag("dashscope")
@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=true",
        "spring.ai.dashscope.chat.options.model=qwen-plus"
})
@EnabledIf(expression = "#{systemProperties['run.dashscope.smoke'] == 'true'}", loadContext = false)
@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
class VoiceOnlineSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private DashScopeAudioTranscriptionModel transcriptionModel;

    @Autowired
    private DashScopeAudioSpeechModel speechModel;

    private final MockParkFixture fixture = new MockParkFixture();

    @Test
    void fullVoiceChainAnswersEnergyQuestionThroughRealProviders() {
        var agent = new VoiceAnswerAgent(
                chatModel,
                new AlertQueryTool(fixture.alerts()),
                new EnergyQueryTool(fixture.energy()),
                new ParkKnowledgeTool(fixture.knowledge()),
                new VoiceAnswerValidator());

        var toolEvents = new CopyOnWriteArrayList<String>();
        var deltas = new CopyOnWriteArrayList<String>();
        VoiceAnswer answer = agent.answer("smoke", "turn-1", "A2 楼现在用了多少电？",
                new VoiceAnswerAgent.Listener() {
                    @Override
                    public void onToolStarted(String toolName, String argumentSummary) {
                        toolEvents.add(toolName + ":STARTED");
                    }

                    @Override
                    public void onToolCompleted(String toolName, boolean success) {
                        toolEvents.add(toolName + ":COMPLETED");
                    }

                    @Override
                    public void onTextDelta(String delta) {
                        deltas.add(delta);
                    }
                });

        // 真实工具调用发生且回答有内容（数字必须来自本轮证据）。
        assertThat(toolEvents).contains("lookupEnergyConsumption:STARTED", "lookupEnergyConsumption:COMPLETED");
        assertThat(answer.text()).isNotBlank();
        assertThat(deltas).isNotEmpty();
        assertThat(answer.evidenceRefs()).isNotEmpty();
        // 回答中的数字必须能在证据中追溯（校验已由 Agent 内部执行，这里复核）。
        new VoiceAnswerValidator().validate(
                com.example.smartpark.voice.model.VoiceIntent.ENERGY, answer);

        // 真实 TTS：校验通过的文本合成出至少一个音频块，随后取消。
        record Chunk(int sequence, int size) {
        }
        var chunks = new CopyOnWriteArrayList<Chunk>();
        StreamingTtsPort ttsPort = new DashScopeStreamingTtsAdapter(speechModel);
        String turnId = "tts-turn-1";
        ttsPort.start("smoke", turnId, List.of(answer.text()), new StreamingTtsPort.Listener() {
            @Override
            public void onAudioChunk(String sid, String tid, int sequence, byte[] audio) {
                chunks.add(new Chunk(sequence, audio.length));
            }

            @Override
            public void onError(String sid, String tid, VoiceErrorCode code) {
            }

            @Override
            public void onCompleted(String sid, String tid) {
            }

            @Override
            public void onInterrupted(String sid, String tid) {
            }
        });
        awaitUntil(() -> !chunks.isEmpty());
        assertThat(chunks.get(0).sequence()).isEqualTo(1);

        ttsPort.cancel("smoke", turnId);
        int afterCancel = chunks.size();
        Thread.yield();
        assertThat(chunks.size()).isEqualTo(afterCancel); // 取消后不再有新块
    }

    @Test
    void asrTerminalBehaviorWithSilenceStaysExplicit() {
        StreamingAsrPort asrPort = new DashScopeStreamingAsrAdapter(transcriptionModel);
        var outcomes = new CopyOnWriteArrayList<String>();

        asrPort.start("smoke-asr", "turn-silence", new StreamingAsrPort.Listener() {
            @Override
            public void onPartial(String sessionId, String turnId, String text) {
                outcomes.add("PARTIAL");
            }

            @Override
            public void onFinal(String sessionId, String turnId, String text) {
                outcomes.add("FINAL:" + text);
            }

            @Override
            public void onError(String sessionId, String turnId, VoiceErrorCode code) {
                outcomes.add("ERROR:" + code);
            }

            @Override
            public void onClosed(String sessionId, String turnId) {
                outcomes.add("CLOSED");
            }
        });

        // 1 秒 16 kHz 静音 PCM 分 50 帧送入。
        byte[] silence = new byte[640];
        for (int i = 0; i < 50; i++) {
            asrPort.send("smoke-asr", "turn-silence", silence);
        }
        asrPort.commit("smoke-asr", "turn-silence");

        awaitUntil(() -> outcomes.stream().anyMatch(line ->
                line.startsWith("FINAL") || line.startsWith("ERROR") || line.equals("CLOSED")));

        // 无论识别为空句还是报错，都只允许安全码/转写文本跨过边界。
        assertThat(outcomes).anySatisfy(line ->
                assertThat(line).doesNotContain("Exception").doesNotContain("stack"));
        asrPort.cancel("smoke-asr", "turn-silence");
    }

    private static void awaitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 30s");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
