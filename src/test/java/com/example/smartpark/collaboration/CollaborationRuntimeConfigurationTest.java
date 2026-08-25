package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.expert.ExpertToolSet;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationRuntimeConfigurationTest {
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
    void invokesOnlySelectedExpertForSingleDomainPlan() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            CollaborationRun run = context.getBean(ExpertCollaborationService.class)
                    .start("A2 夜间能耗升高的原因是什么");
            CollaborationRun completed = awaitTerminal(context.getBean(ExpertCollaborationService.class), run.runId());
            assertThat(completed.status()).isEqualTo(CollaborationRun.RunStatus.COMPLETED);
            assertThat(model.expertPrompts()).containsExactly("ENERGY");
        });
    }

    @Test
    void invokesAllThreeSelectedExpertsForCrossDomainPlan() {
        RoutingChatModel model = AllDependencies.model();
        model.clear();
        runner.run(context -> {
            CollaborationRun run = context.getBean(ExpertCollaborationService.class)
                    .start("A2 夜间能耗升高且门禁告警、冷机离线，是否有关联");
            CollaborationRun completed = awaitTerminal(context.getBean(ExpertCollaborationService.class), run.runId());
            assertThat(completed.status()).isEqualTo(CollaborationRun.RunStatus.COMPLETED);
            assertThat(model.expertPrompts()).containsExactlyInAnyOrder("ENERGY", "DEVICE", "SECURITY");
        });
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
        private final List<String> systems = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ChatResponse call(Prompt prompt) {
            String system = prompt.getSystemMessage().getText();
            systems.add(system);
            String json;
            if (system.contains("collaboration supervisor")) {
                String question = prompt.getUserMessage().getText();
                boolean crossDomain = question.contains("门禁") || question.contains("冷机");
                json = crossDomain
                        ? plan(question, "ENERGY", "DEVICE", "SECURITY")
                        : plan(question, "ENERGY");
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
            String assignments = java.util.Arrays.stream(domains).map(d -> "\"" + d + "\":\"analyze " + d.toLowerCase() + "\"").collect(java.util.stream.Collectors.joining(","));
            return "{\"normalizedQuestion\":\"" + question + "\",\"selectedDomains\":[" + selected + "],\"assignments\":{" + assignments + "},\"selectionReason\":\"question requires selected domains\"}";
        }

        void clear() { systems.clear(); }

        List<String> expertPrompts() {
            return systems.stream().filter(value -> value.contains("park expert"))
                    .map(value -> value.substring(value.indexOf("the ") + 4, value.indexOf(" park expert"))).toList();
        }
    }

    static final class ProbeTool {
        @Tool(name = "probe", description = "test probe")
        public String probe(String input) { return input; }
    }
}
