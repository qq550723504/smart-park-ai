package com.example.smartpark.collaboration;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.smartpark.collaboration.expert.ExpertFindingParser;
import com.example.smartpark.collaboration.expert.ExpertFindingValidator;
import com.example.smartpark.collaboration.expert.ExpertToolSet;
import com.example.smartpark.collaboration.expert.EvidenceLedger;
import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.example.smartpark.collaboration.supervisor.SupervisorPlanner;
import com.example.smartpark.collaboration.supervisor.SupervisorSynthesizer;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class CollaborationRuntimeConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CollaborationRuntimeConfiguration.class);

    private static final BeanOutputConverter<SupervisorPlanModelOutput> SUPERVISOR_OUTPUT_CONVERTER =
            new BeanOutputConverter<>(SupervisorPlanModelOutput.class);
    private static final String SUPERVISOR_SYSTEM_PROMPT =
            "You are the park collaboration supervisor. Return only JSON with normalizedQuestion, "
                    + "selectedDomains, assignments, selectionReason. normalizedQuestion must exactly echo "
                    + "the normalized original question after trimming surrounding whitespace; never paraphrase "
                    + "it or replace an entity. The complete assignments list must preserve every concrete identifier from "
                    + "the original question exactly. Each assignment may contain only the identifiers relevant to its domain "
                    + "and must never add an identifier. Every assignment must be a non-empty string. assignments must contain "
                    + "exactly one {domain, assignment} item for every selectedDomains entry and no duplicate "
                    + "domains. Explanatory assignment text is allowed when it retains that exact entity scope. "
                    + "selectedDomains are advisory; "
                    + "server-owned deterministic routing is authoritative and may independently add or remove "
                    + "expert domains. The only allowed domain literals are exactly ENERGY, DEVICE, SECURITY. "
                    + "Never output entity types, device IDs, or event IDs such as power, equipment, "
                    + "DEV-ENERGY-001, or SEC-ACCESS-001 as a domain.";

    @Bean
    SupervisorPlanner supervisorPlanner() {
        return new SupervisorPlanner();
    }

    @Bean
    SupervisorSynthesizer supervisorSynthesizer() {
        return new SupervisorSynthesizer();
    }

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("collaborationExecutor")
    ExecutorService collaborationExecutor(ExpertCollaborationProperties properties) {
        int maxParallel = properties.getMaxParallel();
        return new ThreadPoolExecutor(maxParallel, maxParallel, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxParallel), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("collaborationRunExecutor")
    ExecutorService collaborationRunExecutor(ExpertCollaborationProperties properties) {
        int maxParallel = properties.getMaxParallel();
        return new ThreadPoolExecutor(maxParallel, maxParallel, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxParallel), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ExpertCollaborationGraph expertCollaborationGraph(
            @Qualifier("energyExpertTools") ObjectProvider<ExpertToolSet> energyProvider,
            @Qualifier("deviceExpertTools") ObjectProvider<ExpertToolSet> deviceProvider,
            @Qualifier("securityExpertTools") ObjectProvider<ExpertToolSet> securityProvider,
            @Qualifier("collaborationExecutor") ExecutorService executor,
            ObjectProvider<ChatModel> modelProvider,
            ExecutionEventPublisher events,
            ExpertCollaborationProperties properties) {
        ExpertToolSet energy = energyProvider.getIfAvailable();
        ExpertToolSet device = deviceProvider.getIfAvailable();
        ExpertToolSet security = securityProvider.getIfAvailable();
        ChatModel model = modelProvider.getIfAvailable();
        if (energy == null || device == null || security == null || model == null) return null;
        Map<ExpertDomain, ExpertCollaborationGraph.Expert> experts = new EnumMap<>(ExpertDomain.class);
        experts.put(ExpertDomain.ENERGY, expert(model, ExpertDomain.ENERGY, energy, events));
        experts.put(ExpertDomain.DEVICE, expert(model, ExpertDomain.DEVICE, device, events));
        experts.put(ExpertDomain.SECURITY, expert(model, ExpertDomain.SECURITY, security, events));
        return new ExpertCollaborationGraph(experts, executor, properties.getExpertTimeout(), events);
    }

    @Bean
    ExpertCollaborationService expertCollaborationService(
            ObjectProvider<ChatModel> modelProvider,
            SupervisorPlanner planner,
            SupervisorSynthesizer synthesizer,
            ObjectProvider<ExpertCollaborationGraph> graphProvider,
            ExecutionEventPublisher events,
            @Qualifier("collaborationRunExecutor") ExecutorService runExecutor,
            ExpertCollaborationProperties properties) {
        ChatModel model = modelProvider.getIfAvailable();
        ExpertCollaborationGraph graph = graphProvider.getIfAvailable();
        if (model == null || graph == null) return null;
        return new ExpertCollaborationService(
                question -> planner.parseAndValidate(question, supervisorModelText(model,
                        SUPERVISOR_SYSTEM_PROMPT + SUPERVISOR_OUTPUT_CONVERTER.getFormat(), question,
                        supervisorProviderOptions())),
                graph,
                (plan, findings) -> synthesizer.parseAndValidate(modelText(model,
                        "You are a tool-free supervisor. Return only JSON with status, selectedDomains, evidenceRefs, confidence, uncertainties. The status must be exactly SUPPORTED, INSUFFICIENT_EVIDENCE, or FAILED. If status is SUPPORTED, select every SUPPORTED finding and copy only its evidence references. If status is INSUFFICIENT_EVIDENCE or FAILED, selectedDomains and evidenceRefs must both be empty and confidence must be 0. Do not write a conclusion; the service derives it verbatim from selected findings.",
                        "plan=" + plan + "\nfindings=" + findings), plan, findings),
                new CollaborationRunStore(), events, runExecutor, properties.getRunTimeout(), Clock.systemUTC());
    }

    private static ExpertCollaborationGraph.Expert expert(ChatModel model, ExpertDomain domain,
                                                           ExpertToolSet toolSet, ExecutionEventPublisher events) {
        return new ExpertCollaborationGraph.Expert() {
            @Override
            public ExpertFinding analyze(String assignment) {
                return analyze(assignment, null);
            }

            @Override
            public ExpertFinding analyze(String assignment, java.util.UUID runId) {
                EvidenceLedger observed = new EvidenceLedger();
                ToolCallback[] callbacks = audited(toolSet.callbacks(), observed, events, runId);
                String response = modelTextWithTools(model,
                        "You are the " + domain.name() + " park expert. Analyze only your assigned domain. Before answering, you must call at least one relevant read-only tool. Do not return INSUFFICIENT_EVIDENCE until relevant tools have been attempted. Return only JSON with domain, status, conclusion, evidenceRefs, confidence, nextChecks. The domain must be exactly " + domain.name() + ". The status must be exactly one of SUPPORTED, INSUFFICIENT_EVIDENCE, FAILED; never use workflow states such as IN_PROGRESS or custom labels such as NO_ASSOCIATION_FOUND. Put a negative result in conclusion and keep the business status as SUPPORTED only when the cited tool result supports it. Cite evidence references ONLY by copying the [[evidence:...]] markers returned with each successful tool result; never invent or reuse a marker from another call.",
                        assignment, callbacks);
                ExpertFinding finding = new ExpertFindingParser().parse(response, domain);
                return new ExpertFindingValidator().validateWithObservations(
                        finding, observed.snapshotObservations(), assignment);
            }
        };
    }

    static ToolCallback audited(ToolCallback callback, EvidenceLedger observed,
                                ExecutionEventPublisher events, java.util.UUID runId) {
        return new AuditedCallback(callback, observed, events, runId);
    }

    private static ToolCallback[] audited(ToolCallback[] callbacks, EvidenceLedger observed,
                                          ExecutionEventPublisher events, java.util.UUID runId) {
        return java.util.Arrays.stream(callbacks)
                .map(callback -> audited(callback, observed, events, runId))
                .toArray(ToolCallback[]::new);
    }

    private static String modelText(ChatModel model, String system, String user) {
        return extract(model.call(new Prompt(new SystemMessage(system), new UserMessage(user))));
    }

    static String supervisorModelText(
            ChatModel model, String system, String user, DashScopeChatOptions options) {
        ChatResponse response;
        try {
            response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options));
        }
        catch (RuntimeException providerFailure) {
            LOG.warn("SUPERVISOR_PROVIDER_CALL");
            throw providerFailure;
        }
        try {
            return extract(response);
        }
        catch (IllegalStateException emptyResponse) {
            LOG.warn("SUPERVISOR_EMPTY_RESPONSE");
            throw emptyResponse;
        }
    }

    private static DashScopeChatOptions supervisorProviderOptions() {
        DashScopeResponseFormat.JsonSchemaConfig schema = DashScopeResponseFormat.JsonSchemaConfig.builder()
                .name("collaboration_supervisor_plan")
                .description("Strict structured output for the collaboration supervisor plan")
                .schema(SUPERVISOR_OUTPUT_CONVERTER.getJsonSchemaMap())
                .strict(true)
                .build();
        return DashScopeChatOptions.builder()
                .responseFormat(DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_SCHEMA)
                        .jsonScheme(schema)
                        .build())
                .build();
    }

    private static String modelTextWithTools(ChatModel model, String system, String user, ToolCallback[] callbacks) {
        var client = org.springframework.ai.chat.client.ChatClient.builder(model).build();
        return extract(client.prompt(new Prompt(new SystemMessage(system), new UserMessage(user)))
                .options(DashScopeChatOptions.builder().toolChoice("required").build())
                .toolCallbacks(callbacks).call().chatResponse());
    }

    private static String extract(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("collaboration model response was blank");
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * Evidence is bound to the specific successful result: every reference is
     * the tool name plus a digest of that exact invocation's arguments, and it
     * is only recorded after the delegate succeeded. The marker is appended to
     * the tool output so the model can copy the precise reference into its
     * finding; generic names or failed calls authorize nothing.
     */
    private record AuditedCallback(ToolCallback delegate, EvidenceLedger observed,
                                   ExecutionEventPublisher events, java.util.UUID runId) implements ToolCallback {
        @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
        @Override public String call(String input) { return invoke(input, arguments -> delegate.call(arguments)); }
        @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            return invoke(input, arguments -> delegate.call(arguments, context));
        }

        private String invoke(String input, java.util.function.UnaryOperator<String> action) {
            String toolName = delegate.getToolDefinition().name();
            publish(ExecutionEventType.TOOL_CALL_STARTED, ExecutionStatus.RUNNING,
                    "调用工具: " + toolName, DisplayPayload.toolCall(toolName, java.util.Map.of()));
            try {
                String result = action.apply(input);
                String ref = "tool:" + toolName + "#" + digest(input);
                observed.record(ref, result, input);
                publish(ExecutionEventType.TOOL_CALL_COMPLETED, ExecutionStatus.RUNNING,
                        "工具调用完成: " + toolName,
                        new DisplayPayload.ToolCallPayload(toolName, java.util.Map.of(), "工具调用完成"));
                return result + "\n[[evidence:" + ref + "]]";
            } catch (RuntimeException failure) {
                publish(ExecutionEventType.TOOL_CALL_FAILED, ExecutionStatus.FAILED,
                        "工具调用失败: " + toolName,
                        DisplayPayload.error(ExecutionStage.TOOL_EXECUTION, "TOOL_CALL_FAILED", true,
                                "工具调用失败"));
                throw failure;
            }
        }

        private void publish(ExecutionEventType type, ExecutionStatus status,
                             String summary, DisplayPayload payload) {
            if (events == null || runId == null) return;
            try {
                events.publish(new ExecutionEvent(java.util.UUID.randomUUID(), runId, 0, java.time.Instant.now(),
                        ExecutionScenario.EXPERT_COLLABORATION, "expert", ExecutionStage.TOOL_EXECUTION,
                        type, status, summary, payload));
            } catch (IllegalStateException closedRun) {
                // The collaboration may have timed out while a provider returned late.
            }
        }
    }

    static String digest(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((input == null ? "" : input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SupervisorPlanModelOutput(
            String normalizedQuestion,
            List<ExpertDomain> selectedDomains,
            List<SupervisorAssignmentModelOutput> assignments,
            String selectionReason) {
    }

    private record SupervisorAssignmentModelOutput(ExpertDomain domain, String assignment) {
    }
}
