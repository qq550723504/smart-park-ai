package com.example.smartpark.collaboration;

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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class CollaborationRuntimeConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CollaborationRuntimeConfiguration.class);


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
                planner::planDeterministically,
                graph,
                (plan, findings) -> synthesizer.synthesize(model, plan, findings),
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
                String primaryEvidence = collectPrimaryEvidence(domain, assignment, callbacks);
                ExpertFindingValidator validator = new ExpertFindingValidator();
                Set<String> primaryEvidenceRefs = validator.usableEvidenceRefs(observed.snapshotObservations());
                String userPrompt = primaryEvidence.isEmpty() ? assignment
                        : assignment + "\n\nServer-collected read-only evidence:\n" + primaryEvidence;
                String response = modelTextWithTools(model,
                        "You are the " + domain.name() + " park expert. Analyze only your assigned domain. Use the server-collected read-only evidence when it is present. A usable observation is SUPPORTED for this domain even when it cannot by itself prove a cross-domain correlation; the supervisor decides correlation. You may call additional relevant read-only tools when needed. Do not return INSUFFICIENT_EVIDENCE until relevant tools have been attempted. Return only JSON with domain, status, conclusion, evidenceRefs, confidence, nextChecks. The domain must be exactly " + domain.name() + ". The status must be exactly one of SUPPORTED, INSUFFICIENT_EVIDENCE, FAILED; never use workflow states such as IN_PROGRESS or custom labels such as NO_ASSOCIATION_FOUND. Put a negative result in conclusion and keep the business status as SUPPORTED only when the cited tool result supports it. Evidence references are bound by the server; include a marker when possible, but never invent or reuse one from another call.",
                        userPrompt, callbacks);
                ExpertFinding finding = bindPrimaryEvidence(
                        new ExpertFindingParser().parse(response, domain), primaryEvidenceRefs);
                return validator.validateWithObservations(
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

    /**
     * Grounds a recognized domain entity before the model turn. This avoids
     * provider-specific forced-tool settings leaking into Spring AI's summary
     * turn while preserving the same audited ToolCallback boundary.
     */
    static String collectPrimaryEvidence(ExpertDomain domain, String assignment, ToolCallback[] callbacks) {
        PrimaryEvidenceSpec spec = primaryEvidenceSpec(domain);
        java.util.regex.Matcher matcher = spec.entityPattern().matcher(assignment == null ? "" : assignment);
        ToolCallback callback = java.util.Arrays.stream(callbacks)
                .filter(candidate -> spec.toolName().equals(candidate.getToolDefinition().name()))
                .findFirst().orElse(null);
        if (callback == null) return "";

        java.util.Set<String> entityIds = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            entityIds.add(matcher.group().toUpperCase(java.util.Locale.ROOT));
        }
        java.util.List<String> evidence = new java.util.ArrayList<>();
        for (String entityId : entityIds) {
            try {
                String result = callback.call("{\"" + spec.argumentName() + "\":\"" + entityId + "\"}");
                if (result != null && !result.isBlank()) evidence.add(result);
            } catch (RuntimeException toolFailure) {
                LOG.warn("PRIMARY_EVIDENCE_COLLECTION_FAILED");
            }
        }
        return String.join("\n", evidence);
    }

    private static PrimaryEvidenceSpec primaryEvidenceSpec(ExpertDomain domain) {
        int insensitive = java.util.regex.Pattern.CASE_INSENSITIVE;
        return switch (domain) {
            case ENERGY -> new PrimaryEvidenceSpec("lookupEnergyConsumption", "meterId",
                    java.util.regex.Pattern.compile("\\bDEV-(?:ENERGY|METER)[A-Z0-9-]*\\b", insensitive));
            case DEVICE -> new PrimaryEvidenceSpec("lookupDeviceStatus", "deviceId",
                    java.util.regex.Pattern.compile("\\bDEV-(?!(?:ENERGY|METER)(?:-|\\b))[A-Z0-9-]+\\b", insensitive));
            case SECURITY -> new PrimaryEvidenceSpec("lookupSecurityEvent", "eventId",
                    java.util.regex.Pattern.compile("\\bSEC-[A-Z0-9-]+\\b", insensitive));
        };
    }

    private record PrimaryEvidenceSpec(String toolName, String argumentName,
                                       java.util.regex.Pattern entityPattern) { }

    static ExpertFinding bindPrimaryEvidence(ExpertFinding modelFinding, Set<String> primaryEvidenceRefs) {
        if (primaryEvidenceRefs == null || primaryEvidenceRefs.isEmpty()) return modelFinding;
        return new ExpertFinding(modelFinding.domain(),
                com.example.smartpark.collaboration.model.FindingStatus.SUPPORTED,
                "Server-bound primary evidence.", primaryEvidenceRefs.stream().sorted().toList(),
                modelFinding.confidence(), modelFinding.nextChecks());
    }

    private static String modelText(ChatModel model, String system, String user) {
        return extract(model.call(new Prompt(new SystemMessage(system), new UserMessage(user))));
    }



    private static String modelTextWithTools(ChatModel model, String system, String user, ToolCallback[] callbacks) {
        var client = org.springframework.ai.chat.client.ChatClient.builder(model).build();
        return extract(client.prompt(new Prompt(new SystemMessage(system), new UserMessage(user)))
                .options(DashScopeChatOptions.builder()
                        .enableThinking(false)
                        .build())
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

}
