package com.example.smartpark;

import com.example.smartpark.model.WorkflowStatus;
import com.example.smartpark.park.AlertPort;
import com.example.smartpark.web.AlertWorkflowController;
import com.example.smartpark.web.ApiExceptionHandler;
import com.example.smartpark.web.WebDtos;
import com.example.smartpark.web.WorkflowEventController;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SensitiveDataTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void workflowHttpDtoExcludesRawGraphStatePromptsHeadersAndApiKeys() throws Exception {
        AlertWorkflow workflow = mock(AlertWorkflow.class);
        AlertPort alertPort = mock(AlertPort.class);
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                "wf-sensitive",
                "ALT-TEMP-001",
                WorkflowStatus.FAILED,
                Map.of(
                        "rawPrompt", "private-prompt-payload",
                        "requestHeaders", Map.of("Authorization", "private-header-token"),
                        "apiKey", "private-api-key",
                        "providerResponse", "private-provider-response"),
                null,
                Optional.empty(),
                null,
                List.of("WORKFLOW_FAILED: Workflow execution failed"),
                4);
        when(workflow.start("ALT-TEMP-001")).thenReturn(snapshot);
        AlertWorkflowController controller = new AlertWorkflowController(workflow, alertPort);

        String json = OBJECT_MAPPER.writeValueAsString(controller.start("ALT-TEMP-001"));

        assertThat(json)
                .doesNotContain(
                        "statePayload",
                        "rawPrompt",
                        "private-prompt-payload",
                        "requestHeaders",
                        "private-header-token",
                        "apiKey",
                        "private-api-key",
                        "providerResponse",
                        "private-provider-response");
    }

    @Test
    void sseEventDtoExcludesWorkflowStateAndAllSensitiveSummaryValues() throws Exception {
        AlertWorkflow workflow = mock(AlertWorkflow.class);
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        WorkflowSnapshot snapshot = snapshot("wf-events-sensitive");
        when(workflow.status(snapshot.workflowId())).thenReturn(snapshot);
        when(publisher.events(snapshot.workflowId())).thenReturn(Flux.just(new WorkflowEvent(
                snapshot.workflowId(),
                1,
                WorkflowEvent.EventType.FAILED,
                "diagnoseAlert",
                Instant.parse("2026-08-23T03:00:00Z"),
                "prompt=private-prompt Authorization: Bearer private-bearer "
                        + "apiKey=private-api-key providerResponse=private-provider-response")));
        WorkflowEventController controller = new WorkflowEventController(workflow, publisher);

        List<ServerSentEvent<WebDtos.WorkflowEventDto>> events = controller.events(snapshot.workflowId())
                .collectList()
                .block(Duration.ofSeconds(2));
        String json = OBJECT_MAPPER.writeValueAsString(events.get(0).data());

        assertThat(json)
                .doesNotContain(
                        "workflowId",
                        "statePayload",
                        "private-prompt",
                        "private-bearer",
                        "private-api-key",
                        "private-provider-response",
                        "providerResponse");
    }

    @Test
    void httpErrorsNeverEchoProviderResponsesHeadersOrCredentials() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SensitiveFailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/task-6-sensitive-failure"))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-provider-response"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-header-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-api-key"))));
    }

    @Test
    void runtimeConfigurationContainsNoCredentialOrAuthorizationLiteral() throws IOException {
        Path repository = Path.of("").toAbsolutePath().normalize();
        List<Path> roots = List.of(
                repository.resolve("pom.xml"),
                repository.resolve(".mvn"),
                repository.resolve("src/main/resources"),
                repository.resolve("src/test/resources"));
        List<String> findings = new ArrayList<>();
        List<Pattern> forbidden = List.of(
                Pattern.compile("\\b" + "sk" + "-[A-Za-z0-9_-]{12,}"),
                Pattern.compile("(?i)\\b" + "Bearer" + "\\s+[A-Za-z0-9._-]+"),
                Pattern.compile("(?m)" + "AI_DASHSCOPE_API_KEY" + "\\s*=\\s*[^\\s#'\"]+"),
                Pattern.compile("\\bAKIA[A-Z0-9]{16}\\b"),
                Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}\\b"),
                Pattern.compile("\\bghp_[A-Za-z0-9]{20,}\\b"));

        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(SensitiveDataTest::isTextConfiguration)
                        .forEach(path -> scan(path, repository, forbidden, findings));
            }
        }

        assertThat(findings).as("secret-like runtime configuration findings").isEmpty();
    }

    private static void scan(
            Path path,
            Path repository,
            List<Pattern> forbidden,
            List<String> findings) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            forbidden.stream()
                    .filter(pattern -> pattern.matcher(content).find())
                    .map(pattern -> repository.relativize(path) + " matched " + pattern.pattern())
                    .forEach(findings::add);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect runtime configuration: " + path, exception);
        }
    }

    private static boolean isTextConfiguration(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.equals("pom.xml")
                || name.endsWith(".properties")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".json")
                || name.endsWith(".toml")
                || name.endsWith(".env")
                || name.endsWith(".xml")
                || name.endsWith(".cmd")
                || name.endsWith(".ps1")
                || name.endsWith(".sh");
    }

    private static WorkflowSnapshot snapshot(String workflowId) {
        return new WorkflowSnapshot(
                workflowId,
                "ALT-TEMP-001",
                WorkflowStatus.FAILED,
                Map.of("rawPrompt", "must-not-cross-dto"),
                null,
                Optional.empty(),
                null,
                List.of("DIAGNOSIS_FAILED: Unable to diagnose alert"),
                1);
    }

    @RestController
    static class SensitiveFailureController {

        @GetMapping("/task-6-sensitive-failure")
        void fail() {
            throw new IllegalStateException(
                    "providerResponse=private-provider-response "
                            + "Authorization=private-header-token apiKey=private-api-key");
        }
    }
}
