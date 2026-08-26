package com.example.smartpark.voice;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.voice.model.ToolCallRecord;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceIntent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Evidence-constrained voice answer agent.
 *
 * Phase 1 classifies intent with one structured call; phase 2 routes
 * deterministically to read-only park tools (real invocations published as
 * tool events); phase 3 streams an answer grounded strictly on this turn's
 * tool evidence, which {@link VoiceAnswerValidator} must accept before the
 * answer may reach TTS. Write requests never reach any tool.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class VoiceAnswerAgent {

    private static final Logger log = LoggerFactory.getLogger(VoiceAnswerAgent.class);

    /** Real-time facts of one turn, published to WS and the unified event stream. */
    public interface Listener {

        void onToolStarted(String toolName, String argumentSummary);

        void onToolCompleted(String toolName, boolean success);

        void onTextDelta(String delta);
    }

    private static final String CLASSIFY_SYSTEM = """
            你是智慧园区语音助手的意图分类器。只输出一个 JSON 对象，不要输出其他内容。
            JSON 字段：intent 取值 ALERT|ENERGY|PARKING_POLICY|CHITCHAT|WRITE_REQUEST；
            alertId、meterId、keyword 仅在用户明确给出或清晰指代时填写，否则省略该字段。
            用户要求执行任何写入、控制、开门等操作时，intent 必须为 WRITE_REQUEST。""";

    private static final String GROUNDED_SYSTEM = """
            你是智慧园区语音助手。只能使用下方“本轮证据”中的事实回答用户问题，
            禁止编造任何数字、编号或政策条款；证据中没有的信息直接说明无法提供。
            政策类回答必须在句尾标注引用，格式为 [doc:文档编号]。回答保持口语化、简洁。""";

    static final String WRITE_REFUSAL = "语音助手只能查询园区只读信息，无法执行写入或控制操作。";
    static final String ASK_ALERT_ID = "请告诉我具体的告警编号，我再为您查询。";
    static final String ASK_METER_ID = "请提供表计编号，我再为您查询用电情况。";

    private final ChatClient chatClient;
    private final AlertQueryTool alertQueryTool;
    private final EnergyQueryTool energyQueryTool;
    private final ParkKnowledgeTool knowledgeTool;
    private final VoiceAnswerValidator validator;

    public VoiceAnswerAgent(
            ChatModel chatModel,
            AlertQueryTool alertQueryTool,
            EnergyQueryTool energyQueryTool,
            ParkKnowledgeTool knowledgeTool,
            VoiceAnswerValidator validator) {
        this.chatClient = ChatClient.builder(Objects.requireNonNull(chatModel)).build();
        this.alertQueryTool = Objects.requireNonNull(alertQueryTool);
        this.energyQueryTool = Objects.requireNonNull(energyQueryTool);
        this.knowledgeTool = Objects.requireNonNull(knowledgeTool);
        this.validator = Objects.requireNonNull(validator);
    }

    public VoiceAnswer answer(String sessionId, String turnId, String question, Listener listener) {
        return answer(sessionId, turnId, question, listener, () -> false);
    }

    /**
     * @param cancelled cooperative cancellation flag; polled before each LLM call
     *                  and on every stream delta so an interrupted turn stops
     *                  consuming tokens immediately.
     */
    public VoiceAnswer answer(String sessionId, String turnId, String question, Listener listener,
                              java.util.function.BooleanSupplier cancelled) {
        Objects.requireNonNull(listener, "listener");
        if (cancelled.getAsBoolean()) {
            throw new AnswerCancelledException();
        }
        com.fasterxml.jackson.databind.JsonNode plan = parseClassification(classify(question));
        if (cancelled.getAsBoolean()) {
            throw new AnswerCancelledException();
        }
        VoiceIntent intent = toIntent(plan.path("intent").asText(""));

        return switch (intent) {
            case WRITE_REQUEST -> templateAnswer(intent, WRITE_REFUSAL);
            case ALERT -> handleAlert(plan, question, listener, cancelled);
            case ENERGY -> handleEnergy(plan, question, listener, cancelled);
            case PARKING_POLICY -> handleParkingPolicy(plan, question, listener, cancelled);
            case CHITCHAT -> streamGrounded(intent, question, List.of(), List.of(), List.of(), listener, cancelled);
        };
    }

    private VoiceAnswer handleAlert(com.fasterxml.jackson.databind.JsonNode plan,
                                    String question, Listener listener,
                                    java.util.function.BooleanSupplier cancelled) {
        String alertId = plan.path("alertId").asText("").trim();
        if (alertId.isEmpty()) {
            return templateAnswer(VoiceIntent.ALERT, ASK_ALERT_ID);
        }
        listener.onToolStarted("lookupAlert", "alertId=" + alertId);
        AlertQueryTool.AlertLookupResult result = alertQueryTool.lookupAlert(alertId);
        boolean lookupSucceeded = result.error() == null && result.alert() != null;
        listener.onToolCompleted("lookupAlert", lookupSucceeded);

        ToolCallRecord call;
        List<String> refs;
        if (result.error() != null || result.alert() == null) {
            call = new ToolCallRecord("lookupAlert", "alertId=" + alertId,
                    "error=" + (result.error() != null ? result.error() : "not found"));
            refs = List.of(alertId);
        } else {
            Alert alert = result.alert();
            call = new ToolCallRecord("lookupAlert", "alertId=" + alertId,
                    "alertId=" + alert.id()
                            + " deviceId=" + alert.deviceId()
                            + " buildingId=" + alert.buildingId()
                            + " riskHint=" + alert.riskHint()
                            + " summary=" + alert.summary());
            refs = List.of(alert.id(), alert.deviceId(), alert.buildingId());
        }
        return streamGrounded(VoiceIntent.ALERT, question, refs,
                List.of(call), List.of(evidenceLine(call)), listener, cancelled);
    }

    private VoiceAnswer handleEnergy(com.fasterxml.jackson.databind.JsonNode plan,
                                     String question, Listener listener,
                                     java.util.function.BooleanSupplier cancelled) {
        String meterId = plan.path("meterId").asText("").trim();
        if (meterId.isEmpty()) {
            return templateAnswer(VoiceIntent.ENERGY, ASK_METER_ID);
        }
        listener.onToolStarted("lookupEnergyConsumption", "meterId=" + meterId);
        EnergyQueryTool.EnergyLookupResult result = energyQueryTool.lookupEnergyConsumption(meterId);
        boolean lookupSucceeded = result.error() == null && result.reading() != null;
        listener.onToolCompleted("lookupEnergyConsumption", lookupSucceeded);

        ToolCallRecord call;
        List<String> refs;
        if (result.error() != null || result.reading() == null) {
            call = new ToolCallRecord("lookupEnergyConsumption", "meterId=" + meterId,
                    "error=" + (result.error() != null ? result.error() : "not found"));
            refs = List.of(meterId);
        } else {
            EnergyReading reading = result.reading();
            call = new ToolCallRecord("lookupEnergyConsumption", "meterId=" + meterId,
                    "meterId=" + reading.meterId()
                            + " buildingId=" + reading.buildingId()
                            + " currentKwh=" + reading.currentKwh()
                            + " baselineKwh=" + reading.baselineKwh()
                            + " peakDemandKw=" + reading.peakDemandKw());
            refs = List.of(reading.meterId(), reading.buildingId());
        }
        return streamGrounded(VoiceIntent.ENERGY, question, refs,
                List.of(call), List.of(evidenceLine(call)), listener, cancelled);
    }

    private VoiceAnswer handleParkingPolicy(com.fasterxml.jackson.databind.JsonNode plan,
                                            String question, Listener listener,
                                            java.util.function.BooleanSupplier cancelled) {
        String keyword = plan.path("keyword").asText("").trim();
        if (keyword.isEmpty()) {
            keyword = question;
        }
        listener.onToolStarted("searchVisitorGuide", "query=" + keyword);
        ParkKnowledgeTool.KnowledgeSearchResult result = knowledgeTool.searchVisitorGuide(keyword);
        listener.onToolCompleted("searchVisitorGuide", true);

        List<String> refs = new ArrayList<>();
        StringJoiner digestJoiner = new StringJoiner("\n");
        for (KnowledgeDocument document : result.documents()) {
            refs.add(document.id());
            digestJoiner.add("documentId=" + document.id()
                    + " title=" + document.title()
                    + " content=" + document.content());
        }
        ToolCallRecord call = new ToolCallRecord("searchVisitorGuide", "query=" + keyword,
                digestJoiner.length() == 0 ? "error=no matching documents" : digestJoiner.toString());
        return streamGrounded(VoiceIntent.PARKING_POLICY, question, refs,
                List.of(call), List.of(evidenceLine(call)), listener, cancelled);
    }

    private VoiceAnswer streamGrounded(VoiceIntent intent,
                                       String question,
                                       List<String> evidenceRefs,
                                       List<ToolCallRecord> toolCalls,
                                       List<String> evidenceLines,
                                       Listener listener,
                                       java.util.function.BooleanSupplier cancelled) {
        String evidenceBlock = evidenceLines.isEmpty()
                ? "本轮证据：（无工具证据；请只做简短寒暄或说明需要更多信息，禁止给出任何数字或编号。）"
                : "本轮证据：\n" + String.join("\n---\n", evidenceLines);

        StringBuilder text = new StringBuilder();
        chatClient.prompt()
                .system(GROUNDED_SYSTEM + "\n\n" + evidenceBlock)
                .user(question)
                .stream()
                .content()
                .doOnNext(delta -> {
                    // Disposing the upstream mid-stream on interrupt saves tokens.
                    if (cancelled.getAsBoolean()) {
                        throw new AnswerCancelledException();
                    }
                    text.append(delta);
                    listener.onTextDelta(delta);
                })
                .blockLast();

        VoiceAnswer answer = new VoiceAnswer(text.toString(), evidenceRefs, toolCalls);
        validator.validate(intent, answer);
        return answer;
    }

    private static String evidenceLine(ToolCallRecord call) {
        return call.toolName() + ": " + call.argumentSummary() + "; " + call.resultDigest();
    }

    private VoiceAnswer templateAnswer(VoiceIntent intent, String text) {
        VoiceAnswer answer = new VoiceAnswer(text, List.of(), List.of());
        validator.validate(intent, answer);
        return answer;
    }

    /** Internal cooperative-cancellation signal; never crosses the port boundary. */
    private static final class AnswerCancelledException extends RuntimeException {
        private AnswerCancelledException() {
            super(null, null, false, false);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private String classify(String question) {
        String content = chatClient.prompt()
                .system(CLASSIFY_SYSTEM)
                .user(question)
                .call()
                .content();
        return content == null ? "" : content;
    }

    private com.fasterxml.jackson.databind.JsonNode parseClassification(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return readEmptyPlan();
        }
        try {
            return JSON_MAPPER.readTree(content.substring(start, end + 1));
        }
        catch (Exception ex) {
            log.debug("unparseable classification payload shape; defaulting to chitchat");
            return readEmptyPlan();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readEmptyPlan() {
        try {
            return JSON_MAPPER.readTree("{}");
        }
        catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static VoiceIntent toIntent(String raw) {
        return switch (raw.trim().toUpperCase().replace('-', '_')) {
            case "ALERT" -> VoiceIntent.ALERT;
            case "ENERGY" -> VoiceIntent.ENERGY;
            case "PARKING_POLICY" -> VoiceIntent.PARKING_POLICY;
            case "WRITE_REQUEST" -> VoiceIntent.WRITE_REQUEST;
            default -> VoiceIntent.CHITCHAT;
        };
    }
}
