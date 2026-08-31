package com.example.smartpark.collaboration;

import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.smartpark.collaboration.expert.ExpertToolSet;
import com.example.smartpark.collaboration.expert.EvidenceLedger;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEventType;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.slf4j.LoggerFactory;

class CollaborationRuntimeConfigurationTest {

    @Test
    void supervisorProviderFailureLogsOnlyAllowlistedStage() {
        Logger logger = (Logger) LoggerFactory.getLogger(CollaborationRuntimeConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Throwable failure = catchThrowable(() -> CollaborationRuntimeConfiguration.supervisorModelText(
                    prompt -> { throw new IllegalStateException("PROVIDER_SENTINEL DEV-SECRET-42"); },
                    "system", "question", DashScopeChatOptions.builder().build()));

            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("SUPERVISOR_PROVIDER_CALL")
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("PROVIDER_SENTINEL", "DEV-SECRET-42"));
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void supervisorEmptyResponseLogsOnlyAllowlistedStage() {
        Logger logger = (Logger) LoggerFactory.getLogger(CollaborationRuntimeConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Throwable failure = catchThrowable(() -> CollaborationRuntimeConfiguration.supervisorModelText(
                    prompt -> null, "system", "question", DashScopeChatOptions.builder().build()));

            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("SUPERVISOR_EMPTY_RESPONSE");
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("smartpark.collaboration.max-parallel=2")
            .withUserConfiguration(AllDependencies.class);

    @Test
    void registersRealRuntimeWhenAllDomainToolSetsAreAvailable() {
        runner.run(context -> {
            assertThat(context.getBean(ExpertCollaborationGraph.class)).isNotNull();
            assertThat(context.getBean(ExpertCollaborationService.class)).isNotNull();
            var executor = context.getBean("collaborationExecutor", ExecutorService.class);
            assertThat(((ThreadPoolExecutor) executor).getMaximumPoolSize()).isEqualTo(2);
            assertThat(((ThreadPoolExecutor) executor).getQueue().remainingCapacity()).isEqualTo(2);
            var runExecutor = (ThreadPoolExecutor) context.getBean("collaborationRunExecutor", ExecutorService.class);
            assertThat(runExecutor.getQueue().remainingCapacity()).isEqualTo(2);
        });
    }

    @Test
    void doesNotEnableRuntimeWhenADomainToolSetIsMissing() {
        new ApplicationContextRunner()
                .withPropertyValues("smartpark.collaboration.max-parallel=2")
                .withUserConfiguration(MissingSecurity.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(ExpertCollaborationGraph.class)).isEmpty();
                    assertThat(context.getBeansOfType(ExpertCollaborationService.class)).isEmpty();
                });
    }

    @Test
    void supervisorSystemPromptStatesTheProviderConfirmationContract() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            ExpertCollaborationService service = context.getBean(ExpertCollaborationService.class);
            CollaborationRun started = service.start("A2 夜间能耗升高的原因是什么");
            assertThat(awaitTerminal(service, started.runId()).status())
                    .isEqualTo(CollaborationRun.RunStatus.COMPLETED);

            assertThat(model.supervisorPrompts()).singleElement().satisfies(prompt ->
                    assertThat(prompt.getSystemMessage().getText()).contains(
                            "normalizedQuestion must exactly echo the normalized original question",
                            "complete assignments list must preserve every concrete identifier",
                            "Each assignment may contain only the identifiers relevant to its domain",
                            "assignments must contain exactly one {domain, assignment} item for every selectedDomains entry and no duplicate domains",
                            "Explanatory assignment text is allowed",
                            "selectedDomains are advisory",
                            "server-owned deterministic routing is authoritative"));
        });
    }

    @Test
    void supervisorPreflightUsesStrictDashScopeSchemaWithTypedAssignmentItems() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            ExpertCollaborationService service = context.getBean(ExpertCollaborationService.class);
            CollaborationRun started = service.start("A2 夜间能耗升高的原因是什么");
            assertThat(awaitTerminal(service, started.runId()).status())
                    .isEqualTo(CollaborationRun.RunStatus.COMPLETED);

            assertThat(model.supervisorPrompts()).hasSize(1);
            Prompt prompt = model.supervisorPrompts().get(0);
            assertThat(prompt.getOptions()).isInstanceOf(DashScopeChatOptions.class);
            DashScopeResponseFormat responseFormat = ((DashScopeChatOptions) prompt.getOptions())
                    .getResponseFormat();
            assertThat(responseFormat.getType()).isEqualTo(DashScopeResponseFormat.Type.JSON_SCHEMA);
            assertThat(responseFormat.getJsonScheme().getStrict()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) responseFormat.getJsonScheme().getSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> assignments = (Map<String, Object>) properties.get("assignments");
            assertThat(assignments).containsEntry("type", "array");
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) assignments.get("items");
            assertThat(item).containsEntry("type", "object")
                    .containsEntry("additionalProperties", false);
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) item.get("required");
            assertThat(required).containsExactlyInAnyOrder("domain", "assignment");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemProperties = (Map<String, Object>) item.get("properties");
            assertThat(itemProperties).containsKeys("domain", "assignment");
        });
    }

    @Test
    void deterministicRoutingDoesNotAddProviderSecurityDomainToEnergyQuestion() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            CollaborationRun run = context.getBean(ExpertCollaborationService.class)
                    .start("A2 夜间能耗升高的原因是什么");
            CollaborationRun completed = awaitTerminal(context.getBean(ExpertCollaborationService.class), run.runId());
            assertThat(completed.status()).isEqualTo(CollaborationRun.RunStatus.COMPLETED);
            assertThat(completed.plan().selectedDomains()).containsExactly(ExpertDomain.ENERGY);
            assertThat(model.expertPrompts()).containsExactly("ENERGY");
            assertThat(model.supervisorPromptCount()).isOne();
        });
    }

    @Test
    void deterministicRoutingKeepsDeviceWhenProviderGroupsOnlyEnergyAndSecurity() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            CollaborationRun run = context.getBean(ExpertCollaborationService.class)
                    .start("A2 夜间能耗升高且门禁告警、冷机离线，是否有关联");
            CollaborationRun completed = awaitTerminal(context.getBean(ExpertCollaborationService.class), run.runId());
            assertThat(completed.status()).isEqualTo(CollaborationRun.RunStatus.COMPLETED);
            assertThat(completed.plan().selectedDomains())
                    .containsExactlyInAnyOrder(ExpertDomain.ENERGY, ExpertDomain.DEVICE, ExpertDomain.SECURITY);
            assertThat(model.expertPrompts()).containsExactlyInAnyOrder("ENERGY", "DEVICE", "SECURITY");
            assertThat(model.supervisorPromptCount()).isOne();
        });
    }

    @Test
    void expertPromptUsesAutomaticToolsWithoutForcingTheSummaryTurn() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            CollaborationRun run = context.getBean(ExpertCollaborationService.class)
                    .start("A2 夜间能耗升高的原因是什么");
            assertThat(awaitTerminal(context.getBean(ExpertCollaborationService.class), run.runId()).status())
                    .isEqualTo(CollaborationRun.RunStatus.COMPLETED);

            assertThat(model.expertSystemPrompts()).isNotEmpty().allSatisfy(prompt ->
                    assertThat(prompt).contains(
                            "Use the server-collected read-only evidence when it is present",
                            "Do not return INSUFFICIENT_EVIDENCE until relevant tools have been attempted"));
            assertThat(model.expertProviderOptions()).isNotEmpty().allSatisfy(options ->
                    assertThat(options.getToolChoice()).isNull());
            assertThat(model.expertProviderOptions()).allSatisfy(options ->
                    assertThat(options.getEnableThinking()).isFalse());
        });
    }

    @Test
    void collectsDomainPrimaryEvidenceThroughTheAuditedCallback() {
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        UUID runId = UUID.randomUUID();
        EvidenceLedger ledger = new EvidenceLedger();
        java.util.concurrent.atomic.AtomicReference<String> input = new java.util.concurrent.atomic.AtomicReference<>();
        ToolCallback callback = namedCallback("lookupEnergyConsumption", arguments -> {
            input.set(arguments);
            return "{\"meterId\":\"DEV-ENERGY-001\",\"currentKwh\":138}";
        });

        String evidence = CollaborationRuntimeConfiguration.collectPrimaryEvidence(
                ExpertDomain.ENERGY,
                "inspect DEV-ENERGY-001 and ignore DEV-HVAC-001",
                new ToolCallback[]{CollaborationRuntimeConfiguration.audited(callback, ledger, events, runId)});

        assertThat(input.get()).isEqualTo("{\"meterId\":\"DEV-ENERGY-001\"}");
        assertThat(evidence).contains("currentKwh", "[[evidence:tool:lookupEnergyConsumption#");
        assertThat(ledger.snapshotObservations()).hasSize(1);
        assertThat(events.history(runId)).extracting(event -> event.eventType())
                .containsExactly(ExecutionEventType.TOOL_CALL_STARTED, ExecutionEventType.TOOL_CALL_COMPLETED);
    }

    @Test
    void skipsPrimaryEvidenceWhenAssignmentHasNoDomainEntity() {
        ToolCallback callback = namedCallback("lookupSecurityEvent", arguments -> {
            throw new AssertionError("callback must not be invoked without a security event ID");
        });

        assertThat(CollaborationRuntimeConfiguration.collectPrimaryEvidence(
                ExpertDomain.SECURITY, "inspect the entrance", new ToolCallback[]{callback})).isEmpty();
    }

    @Test
    void bindsServerOwnedPrimaryReferenceInsteadOfTrustingModelMarkerCopying() {
        EvidenceLedger ledger = new EvidenceLedger();
        ledger.record("tool:lookupDeviceStatus#abc",
                "{\"deviceId\":\"DEV-HVAC-001\",\"status\":\"OFFLINE\"}",
                "{\"deviceId\":\"DEV-HVAC-001\"}");
        var modelFinding = new com.example.smartpark.collaboration.model.ExpertFinding(
                ExpertDomain.DEVICE,
                com.example.smartpark.collaboration.model.FindingStatus.INSUFFICIENT_EVIDENCE,
                "provider copied [[evidence:...]] incorrectly",
                List.of("[[evidence:tool:lookupDeviceStatus#abc]]"), 0, List.of());

        var bound = CollaborationRuntimeConfiguration.bindPrimaryEvidence(
                modelFinding, ledger.snapshot());

        assertThat(bound.status())
                .isEqualTo(com.example.smartpark.collaboration.model.FindingStatus.SUPPORTED);
        assertThat(bound.evidenceRefs()).containsExactly("tool:lookupDeviceStatus#abc");
        assertThat(bound.conclusion()).isEqualTo("Server-bound primary evidence.");

        var validated = new com.example.smartpark.collaboration.expert.ExpertFindingValidator()
                .validateWithObservations(bound, ledger.snapshotObservations(), "inspect DEV-HVAC-001");
        assertThat(validated.status())
                .isEqualTo(com.example.smartpark.collaboration.model.FindingStatus.SUPPORTED);
        assertThat(validated.conclusion()).contains("DEV-HVAC-001", "OFFLINE");
    }

    @Test
    void publishesToolLifecycleEventsForTheCurrentCollaborationRun() {
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        UUID runId = UUID.randomUUID();
        EvidenceLedger ledger = new EvidenceLedger();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return new ToolDefinition() {
                    @Override public String name() { return "probe"; }
                    @Override public String description() { return "test probe"; }
                    @Override public String inputSchema() { return "{}"; }
                };
            }

            @Override public String call(String input) { return "ok"; }
        };

        ToolCallback audited = CollaborationRuntimeConfiguration.audited(
                delegate, ledger, events, runId);
        assertThat(audited.call("{\"deviceId\":\"D1\"}")).contains("[[evidence:");
        assertThat(events.history(runId)).extracting(event -> event.eventType())
                .containsExactly(
                        com.example.smartpark.execution.model.ExecutionEventType.TOOL_CALL_STARTED,
                        com.example.smartpark.execution.model.ExecutionEventType.TOOL_CALL_COMPLETED);

        UUID failedRunId = UUID.randomUUID();
        ToolCallback failedDelegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override public String call(String input) {
                throw new IllegalStateException("provider failed");
            }
        };
        ToolCallback auditedFailure = CollaborationRuntimeConfiguration.audited(
                failedDelegate, new EvidenceLedger(), events, failedRunId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auditedFailure.call("{}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events.history(failedRunId)).extracting(event -> event.eventType())
                .containsExactly(
                        com.example.smartpark.execution.model.ExecutionEventType.TOOL_CALL_STARTED,
                        com.example.smartpark.execution.model.ExecutionEventType.TOOL_CALL_FAILED);
    }

    private static CollaborationRun awaitTerminal(ExpertCollaborationService service, UUID id) {
        long deadline = System.currentTimeMillis() + 5000;
        CollaborationRun run;
        do {
            run = service.get(id);
            if (run.status() != CollaborationRun.RunStatus.RUNNING) return run;
            try { Thread.sleep(10); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for collaboration run", ex);
            }
        } while (System.currentTimeMillis() < deadline);
        return run;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExpertCollaborationProperties.class)
    @Import(CollaborationRuntimeConfiguration.class)
    static class AllDependencies {
        private static final RoutingChatModel MODEL = new RoutingChatModel();

        static RoutingChatModel model() { return MODEL; }

        @Bean ChatModel chatModel() { return MODEL; }
        @Bean(name = "energyExpertTools") ExpertToolSet energy() { return ExpertToolSet.of(new ProbeTool()); }
        @Bean(name = "deviceExpertTools") ExpertToolSet device() { return ExpertToolSet.of(new ProbeTool()); }
        @Bean(name = "securityExpertTools") ExpertToolSet security() { return ExpertToolSet.of(new ProbeTool()); }
        @Bean ExecutionEventPublisher events() { return new InMemoryExecutionEventPublisher(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExpertCollaborationProperties.class)
    @Import(CollaborationRuntimeConfiguration.class)
    static class MissingSecurity {
        @Bean ChatModel chatModel() { return new RoutingChatModel(); }
        @Bean(name = "energyExpertTools") ExpertToolSet energy() { return ExpertToolSet.of(new ProbeTool()); }
        @Bean(name = "deviceExpertTools") ExpertToolSet device() { return ExpertToolSet.of(new ProbeTool()); }
        @Bean ExecutionEventPublisher events() { return new InMemoryExecutionEventPublisher(); }
    }

    static final class RoutingChatModel implements ChatModel {
        private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ChatResponse call(Prompt prompt) {
            String system = prompt.getSystemMessage().getText();
            prompts.add(prompt);
            String json;
            if (system.contains("collaboration supervisor")) {
                String question = prompt.getUserMessage().getText();
                boolean crossDomain = question.contains("门禁") || question.contains("冷机");
                json = crossDomain
                        ? plan(question, "ENERGY", "SECURITY")
                        : plan(question, "ENERGY", "SECURITY");
            } else if (system.contains("park expert")) {
                String domain = system.substring(system.indexOf("the ") + 4, system.indexOf(" park expert"));
                json = "{\"domain\":\"" + domain + "\",\"status\":\"INSUFFICIENT_EVIDENCE\",\"conclusion\":\"insufficient evidence\",\"evidenceRefs\":[],\"confidence\":0,\"nextChecks\":[\"collect evidence\"]}";
            } else {
                json = "{\"status\":\"INSUFFICIENT_EVIDENCE\",\"selectedDomains\":[],\"evidenceRefs\":[],\"confidence\":0,\"uncertainties\":[\"more evidence needed\"]}";
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        }

        private static String plan(String question, String... domains) {
            String selected = java.util.Arrays.stream(domains).map(d -> "\"" + d + "\"").collect(java.util.stream.Collectors.joining(","));
            String assignments = java.util.Arrays.stream(domains)
                    .map(d -> "{\"domain\":\"" + d + "\",\"assignment\":\"analyze "
                            + d.toLowerCase() + " for " + question + "\"}")
                    .collect(java.util.stream.Collectors.joining(","));
            return "{\"normalizedQuestion\":\"" + question + "\",\"selectedDomains\":[" + selected + "],\"assignments\":[" + assignments + "],\"selectionReason\":\"question requires selected domains\"}";
        }

        void clear() { prompts.clear(); }

        List<String> expertPrompts() {
            return prompts.stream().map(prompt -> prompt.getSystemMessage().getText())
                    .filter(value -> value.contains("park expert"))
                    .map(value -> value.substring(value.indexOf("the ") + 4, value.indexOf(" park expert"))).toList();
        }

        List<String> expertSystemPrompts() {
            return prompts.stream().map(prompt -> prompt.getSystemMessage().getText())
                    .filter(value -> value.contains("park expert"))
                    .toList();
        }

        List<DashScopeChatOptions> expertProviderOptions() {
            return prompts.stream()
                    .filter(prompt -> prompt.getSystemMessage().getText().contains("park expert"))
                    .map(Prompt::getOptions)
                    .map(DashScopeChatOptions.class::cast)
                    .toList();
        }

        long supervisorPromptCount() {
            return supervisorPrompts().size();
        }

        List<Prompt> supervisorPrompts() {
            return prompts.stream()
                    .filter(prompt -> prompt.getSystemMessage().getText().contains("collaboration supervisor"))
                    .toList();
        }
    }

    static final class ProbeTool {
        @Tool(name = "probe", description = "test probe")
        public String probe(String input) { return input; }
    }

    private static ToolCallback namedCallback(String name,
                                              java.util.function.Function<String, String> action) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return new ToolDefinition() {
                    @Override public String name() { return name; }
                    @Override public String description() { return "test callback"; }
                    @Override public String inputSchema() { return "{}"; }
                };
            }

            @Override public String call(String input) { return action.apply(input); }
        };
    }
}
