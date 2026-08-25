package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.expert.ExpertFindingParser;
import com.example.smartpark.collaboration.expert.ExpertFindingValidator;
import com.example.smartpark.collaboration.expert.ExpertToolSet;
import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.example.smartpark.collaboration.supervisor.SupervisorPlanner;
import com.example.smartpark.collaboration.supervisor.SupervisorSynthesizer;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
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

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class CollaborationRuntimeConfiguration {

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
        return Executors.newFixedThreadPool(properties.getMaxParallel());
    }

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("collaborationRunExecutor")
    ExecutorService collaborationRunExecutor(ExpertCollaborationProperties properties) {
        return Executors.newFixedThreadPool(properties.getMaxParallel());
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
        experts.put(ExpertDomain.ENERGY, expert(model, ExpertDomain.ENERGY, energy));
        experts.put(ExpertDomain.DEVICE, expert(model, ExpertDomain.DEVICE, device));
        experts.put(ExpertDomain.SECURITY, expert(model, ExpertDomain.SECURITY, security));
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
                question -> planner.parseAndValidate(question, modelText(model,
                        "You are the park collaboration supervisor. Return only JSON with normalizedQuestion, selectedDomains, assignments, selectionReason. Select only domains required by the question.", question)),
                graph,
                (plan, findings) -> synthesizer.parseAndValidate(modelText(model,
                        "You are a tool-free supervisor. Return only JSON with status, conclusion, evidenceRefs, confidence, uncertainties. Use only the supplied findings and evidence references.",
                        "plan=" + plan + "\nfindings=" + findings), plan, findings),
                new CollaborationRunStore(), events, runExecutor, properties.getRunTimeout(), Clock.systemUTC());
    }

    private static ExpertCollaborationGraph.Expert expert(ChatModel model, ExpertDomain domain, ExpertToolSet toolSet) {
        return assignment -> {
            Set<String> observed = new HashSet<>();
            ToolCallback[] callbacks = audited(toolSet.callbacks(), observed);
            String response = modelTextWithTools(model,
                    "You are the " + domain.name() + " park expert. Analyze only your assigned domain. Return only JSON with domain, status, conclusion, evidenceRefs, confidence, nextChecks. Cite evidence references ONLY by copying the [[evidence:...]] markers returned with each successful tool result; never invent or reuse a marker from another call.",
                    assignment, callbacks);
            ExpertFinding finding = new ExpertFindingParser().parse(response, domain);
            return new ExpertFindingValidator().validate(finding, observed);
        };
    }

    private static ToolCallback[] audited(ToolCallback[] callbacks, Set<String> observed) {
        return java.util.Arrays.stream(callbacks).map(callback -> new AuditedCallback(callback, observed)).toArray(ToolCallback[]::new);
    }

    private static String modelText(ChatModel model, String system, String user) {
        return extract(model.call(new Prompt(new SystemMessage(system), new UserMessage(user))));
    }

    private static String modelTextWithTools(ChatModel model, String system, String user, ToolCallback[] callbacks) {
        var client = org.springframework.ai.chat.client.ChatClient.builder(model).build();
        return extract(client.prompt(new Prompt(new SystemMessage(system), new UserMessage(user)))
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
    private record AuditedCallback(ToolCallback delegate, Set<String> observed) implements ToolCallback {
        @Override public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
        @Override public String call(String input) { return invoke(input, arguments -> delegate.call(arguments)); }
        @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            return invoke(input, arguments -> delegate.call(arguments, context));
        }

        private String invoke(String input, java.util.function.UnaryOperator<String> action) {
            String result = action.apply(input);
            String ref = "tool:" + delegate.getToolDefinition().name() + "#" + digest(input);
            observed.add(ref);
            return result + "\n[[evidence:" + ref + "]]";
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
