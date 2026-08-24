# Smart Park Read-only MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default-off, model-independent, read-only MCP Server that exposes exactly three safe smart-park query tools over stateless Streamable HTTP.

**Architecture:** Add a dedicated `adapter.mcp` inbound adapter backed only by `AlertPort`, `EnergyPort`, and `KnowledgePort`. Register its Spring AI `@Tool` methods through one conditional `MethodToolCallbackProvider`; let the official Spring AI 1.1.2 WebMVC MCP Starter own JSON-RPC and HTTP transport. Return MCP-specific allowlisted records so internal domain fields never become protocol output.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, MCP Java SDK 0.17.0 (transitive), JUnit 5, AssertJ, Jackson, Maven Wrapper, MCP Inspector, Vue/Vite regression build.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-readonly-mcp-design.md`

## Global Constraints

- Keep `spring-ai.version` at `1.1.2` and `spring-ai-alibaba.version` at `1.1.2.2`; add no explicit MCP SDK version outside the Spring AI BOM.
- Use `spring-ai-starter-mcp-server-webmvc`; do not add WebFlux, STDIO, old SSE, a second Spring Boot process, or a new Maven module.
- Expose exactly `smartpark_lookup_alert`, `smartpark_lookup_energy`, and `smartpark_search_knowledge`.
- MCP stays disabled unless `SMARTPARK_MCP_ENABLED=true`; it must run with `SPRING_AI_DASHSCOPE_ENABLED=false` and no model API key.
- Use `protocol: STATELESS`, `type: SYNC`, endpoint `/mcp`, and the Spring AI 1.1.2 property prefix `spring.ai.mcp.server.streamable-http`.
- Disable MCP annotation scanning and every non-tool capability; integration tests lock the discovered tool set to exactly three names.
- Never return `Alert.summary`, `Alert.evidence`, `KnowledgeDocument.content`, security event data, diagnosis, approval, work-order, prompt, model response, credential, or control capability.
- Do not create fake authentication, fake user audit entries, production network claims, or an external MCP Client in this slice.
- Preserve existing REST, SSE, Agent, workflow, customer-service, RAG, UI, and Mock behavior.
- Use explicit Git paths for every commit; do not stage unrelated work.

## File Structure

### Create

- `src/main/java/com/example/smartpark/adapter/mcp/McpToolResults.java` — MCP-only allowlisted result and error records.
- `src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpTools.java` — three read-only tool methods, validation, Port calls, mapping, and sanitized logging.
- `src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpConfiguration.java` — conditional tool and provider registration.
- `src/test/java/com/example/smartpark/adapter/mcp/SmartParkMcpToolsTest.java` — behavior, bounds, deterministic mapping, and safe error tests.
- `src/test/java/com/example/smartpark/adapter/mcp/McpDataBoundaryTest.java` — serialized-output and DTO field allowlist tests.
- `src/test/java/com/example/smartpark/architecture/McpAdapterBoundaryTest.java` — source/import dependency guard for the MCP adapter.
- `src/test/java/com/example/smartpark/adapter/mcp/McpEnabledContextTest.java` — enabled/offline Spring context and exact provider schema test.
- `src/test/java/com/example/smartpark/adapter/mcp/McpProtocolIntegrationTest.java` — real stateless Streamable HTTP initialize/list/call test using the official Java SDK.

### Modify

- `pom.xml` — add the BOM-managed Spring AI WebMVC MCP Server Starter.
- `src/main/resources/application.yml` — add the single feature flag and locked-down 1.1.2 MCP server settings.
- `src/test/java/com/example/smartpark/SmartParkApplicationTest.java` — prove the default application registers no MCP provider.
- `README.md` — document security limits, startup, Inspector calls, outputs, and opt-in Codex configuration.

---

### Task 1: Build the MCP-only safe tool adapter

**Files:**

- Create: `src/main/java/com/example/smartpark/adapter/mcp/McpToolResults.java`
- Create: `src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpTools.java`
- Create: `src/test/java/com/example/smartpark/adapter/mcp/SmartParkMcpToolsTest.java`
- Create: `src/test/java/com/example/smartpark/adapter/mcp/McpDataBoundaryTest.java`
- Create: `src/test/java/com/example/smartpark/architecture/McpAdapterBoundaryTest.java`

**Interfaces:**

- Consumes: `AlertPort#getAlert(String)`, `EnergyPort#getLatestEnergyReading(String)`, `KnowledgePort#rankedSearch(KnowledgeDomain, String)`.
- Produces: `SmartParkMcpTools#lookupAlert(String)`, `#lookupEnergy(String)`, and `#searchKnowledge(String, String)`.
- Produces: `McpToolResults` records `McpError`, `AlertData`, `AlertLookupResult`, `EnergyData`, `EnergyLookupResult`, `KnowledgeMatchData`, `KnowledgeData`, and `KnowledgeSearchResult`.

- [ ] **Step 1: Write failing tool behavior tests**

Create `SmartParkMcpToolsTest` in package `com.example.smartpark.adapter.mcp`. Use `MockParkFixture` and add these exact cases:

```java
class SmartParkMcpToolsTest {
    private final MockParkFixture fixture = new MockParkFixture();
    private final SmartParkMcpTools tools = new SmartParkMcpTools(
            fixture.alerts(), fixture.energy(), fixture.knowledge());

    @Test
    void returnsAllowlistedAlertMetadata() {
        var result = tools.lookupAlert(" ALT-ENERGY-001 ");
        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.data().alertId()).isEqualTo("ALT-ENERGY-001");
        assertThat(result.data().deviceId()).isEqualTo("DEV-ENERGY-001");
        assertThat(result.data().classification()).isEqualTo("ENERGY");
        assertThat(result.data().riskHint()).isEqualTo("HIGH");
    }

    @Test
    void rejectsInvalidAlertIdWithoutCallingThePort() {
        AlertPort alertPort = mock(AlertPort.class);
        SmartParkMcpTools subject = new SmartParkMcpTools(alertPort, fixture.energy(), fixture.knowledge());
        var result = subject.lookupAlert("missing-alert");
        assertThat(result.ok()).isFalse();
        assertThat(result.error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(alertPort);
    }

    @Test
    void mapsUnknownValidAlertToSafeNotFound() {
        var result = tools.lookupAlert("ALT-UNKNOWN-001");
        assertThat(result.ok()).isFalse();
        assertThat(result.error().code()).isEqualTo(McpToolResults.ErrorCode.NOT_FOUND);
        assertThat(result.error().message()).isEqualTo("Requested park record was not found.");
    }

    @Test
    void returnsEnergyReadingAndDerivedVariance() {
        var result = tools.lookupEnergy("DEV-ENERGY-001");
        assertThat(result.ok()).isTrue();
        assertThat(result.data().currentKwh()).isEqualTo(138.0);
        assertThat(result.data().baselineKwh()).isEqualTo(100.0);
        assertThat(result.data().varianceKwh()).isEqualTo(38.0);
        assertThat(result.data().varianceRatio()).isEqualTo(0.38);
    }

    @Test
    void rejectsInvalidKnowledgeDomain() {
        var result = tools.searchKnowledge("energy", "PRIVATE_OPERATIONS");
        assertThat(result.ok()).isFalse();
        assertThat(result.error().code()).isEqualTo(McpToolResults.ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        EnergyPort failing = meterId -> {
            throw new IllegalStateException("private meter value for " + meterId);
        };
        SmartParkMcpTools subject = new SmartParkMcpTools(fixture.alerts(), failing, fixture.knowledge());
        var result = subject.lookupEnergy("DEV-ENERGY-001");
        assertThat(result.ok()).isFalse();
        assertThat(result.error().code()).isEqualTo(McpToolResults.ErrorCode.INTERNAL_ERROR);
        assertThat(result.error().message()).isEqualTo("Tool execution failed.");
        assertThat(result.toString()).doesNotContain("private meter value", "DEV-ENERGY-001");
    }
}
```

Add a stub `KnowledgePort` returning six `KnowledgeMatch` objects with scores `0.1` through `0.6`; assert `searchKnowledge` returns scores `0.6, 0.5, 0.4, 0.3, 0.2` in that order and never returns document content. Add blank and 501-character query cases that expect `INVALID_ARGUMENT` without calling the Port.

- [ ] **Step 2: Write failing serialization and architecture guards**

Create `McpDataBoundaryTest`:

```java
private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

@Test
void serializedMcpResultsExcludeInternalFieldsAndBodies() throws Exception {
    MockParkFixture fixture = new MockParkFixture();
    SmartParkMcpTools tools = new SmartParkMcpTools(fixture.alerts(), fixture.energy(), fixture.knowledge());
    String alertJson = JSON.writeValueAsString(tools.lookupAlert("ALT-ENERGY-001"));
    String knowledgeJson = JSON.writeValueAsString(tools.searchKnowledge("energy", "ALERT_OPERATIONS"));
    assertThat(alertJson).doesNotContain("summary", "evidence", "diagnosis", "approval", "workOrder");
    assertThat(knowledgeJson).doesNotContain("content", "embedding", "vector");
}

@Test
void mcpDtoFieldsAreExactAllowlists() {
    assertThat(componentNames(McpToolResults.AlertData.class)).containsExactly(
            "alertId", "parkId", "buildingId", "deviceId", "classification", "riskHint", "occurredAt");
    assertThat(componentNames(McpToolResults.KnowledgeMatchData.class)).containsExactly(
            "documentId", "title", "domain", "tags", "score", "updatedAt");
}

private static List<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
}
```

Create `McpAdapterBoundaryTest` using the existing `AdapterDependencyTest` `Files.walk` pattern. Reject these strings in every source below `adapter/mcp`:

```java
private static final List<String> FORBIDDEN_REFERENCES = List.of(
        "com.example.smartpark.web",
        "com.example.smartpark.agent",
        "com.example.smartpark.workflow",
        "com.example.smartpark.audit",
        "com.example.smartpark.feedback",
        "KnowledgeAdminPort",
        "WorkOrderPort",
        "com.example.smartpark.adapter.mock",
        "com.example.smartpark.adapter.rag",
        "@Component",
        "@McpTool");
```

- [ ] **Step 3: Run the focused tests and verify the red phase**

```powershell
.\mvnw.cmd -Dtest=SmartParkMcpToolsTest,McpDataBoundaryTest,McpAdapterBoundaryTest test
```

Expected: test compilation fails because `McpToolResults` and `SmartParkMcpTools` do not exist.

- [ ] **Step 4: Implement the MCP result records**

Create a non-instantiable `McpToolResults` class with these exact public types:

```java
public static final String NOTICE = "Mock park data only. Read-only; no device control.";
public static final String INVALID_MESSAGE = "Invalid tool argument.";
public static final String NOT_FOUND_MESSAGE = "Requested park record was not found.";
public static final String INTERNAL_MESSAGE = "Tool execution failed.";

public enum ErrorCode { INVALID_ARGUMENT, NOT_FOUND, INTERNAL_ERROR }
public record McpError(ErrorCode code, String message) { }
public record AlertData(String alertId, String parkId, String buildingId, String deviceId,
        String classification, String riskHint, Instant occurredAt) { }
public record AlertLookupResult(boolean ok, AlertData data, McpError error, String notice) { }
public record EnergyData(String meterId, String parkId, String buildingId, Instant measuredAt,
        double currentKwh, double baselineKwh, double peakDemandKw,
        double varianceKwh, double varianceRatio) { }
public record EnergyLookupResult(boolean ok, EnergyData data, McpError error, String notice) { }
public record KnowledgeMatchData(String documentId, String title, String domain,
        List<String> tags, double score, Instant updatedAt) { }
public record KnowledgeData(String query, String domain, List<KnowledgeMatchData> matches) { }
public record KnowledgeSearchResult(boolean ok, KnowledgeData data, McpError error, String notice) { }
```

Add factory methods `invalidArgument()`, `notFound()`, and `internalError()` returning the fixed messages. Add compact constructors so successful results require non-null `data` and null `error`, failed results require null `data` and non-null `error`, metadata is non-null, and lists use `List.copyOf`.

- [ ] **Step 5: Implement the three annotated tool methods**

Create `SmartParkMcpTools` without a Spring stereotype. Define:

```java
private static final Pattern ALERT_ID = Pattern.compile("ALT-[A-Z0-9-]{1,120}");
private static final Pattern METER_ID = Pattern.compile("DEV-[A-Z0-9-]{1,120}");
private static final int MAX_KNOWLEDGE_QUERY_LENGTH = 500;
private static final int MAX_KNOWLEDGE_MATCHES = 5;
```

Implement `normalize` as `value == null ? "" : value.trim()` so null arguments enter the fixed validation path rather than throwing before error mapping.

Use exact tool names and allowlisted mappings:

```java
@Tool(name = "smartpark_lookup_alert",
        description = "Read allowlisted metadata for one Mock park alert. Returns no summary, evidence, security event, identity data, or control capability.")
public McpToolResults.AlertLookupResult lookupAlert(
        @ToolParam(description = "Alert ID matching ALT-[A-Z0-9-], for example ALT-ENERGY-001") String alertId) {
    String normalized = normalize(alertId);
    if (!ALERT_ID.matcher(normalized).matches()) {
        return new McpToolResults.AlertLookupResult(false, null, McpToolResults.invalidArgument(), McpToolResults.NOTICE);
    }
    try {
        Alert alert = alertPort.getAlert(normalized);
        var data = new McpToolResults.AlertData(alert.id(), alert.parkId(), alert.buildingId(), alert.deviceId(),
                alert.classification().name(), alert.riskHint().name(), alert.occurredAt());
        return new McpToolResults.AlertLookupResult(true, data, null, McpToolResults.NOTICE);
    }
    catch (IllegalArgumentException exception) {
        return new McpToolResults.AlertLookupResult(false, null, McpToolResults.notFound(), McpToolResults.NOTICE);
    }
    catch (RuntimeException exception) {
        logFailure("smartpark_lookup_alert", exception);
        return new McpToolResults.AlertLookupResult(false, null, McpToolResults.internalError(), McpToolResults.NOTICE);
    }
}
```

Implement `smartpark_lookup_energy` with identical validation/error structure, mapping `varianceKwh()` and `varianceRatio()`. Implement `smartpark_search_knowledge(query, domain)` by parsing only the two `KnowledgeDomain` enum names, sorting score descending then document ID, limiting to five, and mapping only ID/title/domain/tags/score/update time. Knowledge no-match is `ok=true` with an empty list. Log only tool name and `exception.getClass().getSimpleName()`; never log an input or exception message.

- [ ] **Step 6: Run focused tests until green**

```powershell
.\mvnw.cmd -Dtest=SmartParkMcpToolsTest,McpDataBoundaryTest,McpAdapterBoundaryTest test
```

Expected: all focused tests pass and no test input or Port exception message appears in logs.

- [ ] **Step 7: Commit the safe adapter slice**

```powershell
git add -- 'src/main/java/com/example/smartpark/adapter/mcp/McpToolResults.java' 'src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpTools.java' 'src/test/java/com/example/smartpark/adapter/mcp/SmartParkMcpToolsTest.java' 'src/test/java/com/example/smartpark/adapter/mcp/McpDataBoundaryTest.java' 'src/test/java/com/example/smartpark/architecture/McpAdapterBoundaryTest.java'
git commit -m "feat: add safe readonly MCP tools"
```

### Task 2: Wire the official stateless WebMVC MCP server

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpConfiguration.java`
- Modify: `src/test/java/com/example/smartpark/SmartParkApplicationTest.java`
- Create: `src/test/java/com/example/smartpark/adapter/mcp/McpEnabledContextTest.java`
- Create: `src/test/java/com/example/smartpark/adapter/mcp/McpProtocolIntegrationTest.java`

- [ ] **Step 1: Add the BOM-managed official Spring AI MCP WebMVC starter**

Add this dependency without an explicit version because the repository already imports the Spring AI BOM:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

Confirm Maven resolves one coherent Spring AI/MCP SDK graph:

```powershell
.\mvnw.cmd dependency:tree "-Dincludes=org.springframework.ai:*,io.modelcontextprotocol:*"
```

Expected: Spring AI MCP server artifacts resolve at `1.1.2` and the MCP Java SDK resolves once, with no manually pinned duplicate.

- [ ] **Step 2: Write failing context tests for default-off and explicit enablement**

Extend `SmartParkApplicationTest` so its existing default application context asserts that no `ToolCallbackProvider` bean exists when `SMARTPARK_MCP_ENABLED` is absent:

```java
@Autowired
private ApplicationContext applicationContext;

@Test
void mcpToolProviderIsDisabledByDefault() {
    assertThat(applicationContext.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
}
```

Add `@AutoConfigureMockMvc`, inject `MockMvc`, and prove the transport is absent as well:

```java
@Test
void mcpEndpointIsUnavailableByDefault() throws Exception {
    mockMvc.perform(post("/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isNotFound());
}
```

Create `McpEnabledContextTest` using:

```java
@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=false",
        "smartpark.mcp.enabled=true"
})
class McpEnabledContextTest {
    @Autowired
    @Qualifier("smartParkMcpToolCallbackProvider")
    private ToolCallbackProvider provider;
}
```

Assert the provider exposes exactly these three callback names and no existing Agent tools:

```text
smartpark_lookup_alert
smartpark_lookup_energy
smartpark_search_knowledge
```

Also assert each callback has a non-blank description and input schema, and that the knowledge schema requires both `query` and `domain`.

- [ ] **Step 3: Write a failing real-protocol integration test with the official Java SDK**

Create `McpProtocolIntegrationTest` with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and the same two properties as the enabled context test. Construct the SDK client against the actual endpoint:

```java
var transport = HttpClientStreamableHttpTransport
        .builder("http://127.0.0.1:" + port)
        .endpoint("/mcp")
        .build();

try (McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(5))
        .build()) {
    client.initialize();
    // list and call tools here
}
```

The test must:

1. Assert `client.listTools()` returns exactly the three approved tool names.
2. Call `smartpark_lookup_alert` with `alertId=ALT-ENERGY-001` and assert `isError` is false.
3. Assert `structuredContent()` is null and there is exactly one `McpSchema.TextContent` item.
4. Parse that text as JSON with Jackson and assert the safe result contract, including `notice`.
5. Assert the JSON contains none of `summary`, `evidence`, `content`, identity fields, workflow methods, or device-control methods.
6. Call `smartpark_search_knowledge` with an invalid domain and assert the JSON result is the fixed `INVALID_ARGUMENT` business envelope without reflected input.

Use `new McpSchema.CallToolRequest(toolName, arguments)` for calls; do not bypass HTTP by invoking callbacks directly.

- [ ] **Step 4: Run the new tests and confirm they fail for the missing server wiring**

```powershell
.\mvnw.cmd -Dtest=SmartParkApplicationTest,McpEnabledContextTest,McpProtocolIntegrationTest test
```

Expected: enabled-context/protocol tests fail because the provider and endpoint do not exist yet. The default-off assertion should pass.

- [ ] **Step 5: Add narrowly scoped conditional MCP configuration**

Create `SmartParkMcpConfiguration` without component-scanning the tool class:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartpark.mcp.enabled", havingValue = "true")
public class SmartParkMcpConfiguration {

    @Bean
    SmartParkMcpTools smartParkMcpTools(AlertPort alertPort, EnergyPort energyPort,
            KnowledgePort knowledgePort) {
        return new SmartParkMcpTools(alertPort, energyPort, knowledgePort);
    }

    @Bean
    ToolCallbackProvider smartParkMcpToolCallbackProvider(SmartParkMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

Keep this adapter dependent only on the three application Ports. Do not inject existing Agent `@Tool` beans, repositories, controllers, or workflow services.

- [ ] **Step 6: Add default-off, stateless, tools-only MCP configuration**

Merge these keys into the existing YAML without replacing the current DashScope or customer configuration:

```yaml
spring:
  ai:
    dashscope:
      # keep the existing subtree unchanged
    mcp:
      server:
        enabled: ${smartpark.mcp.enabled}
        protocol: STATELESS
        type: SYNC
        name: smart-park-readonly
        version: 0.1.0
        instructions: >-
          Mock smart-park read-only queries only. No identity data, knowledge body,
          workflow mutation, or device control.
        annotation-scanner:
          enabled: false
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false
        streamable-http:
          mcp-endpoint: /mcp

smartpark:
  customer:
    # keep the existing subtree unchanged
  mcp:
    enabled: ${SMARTPARK_MCP_ENABLED:false}
```

The Spring AI `annotation-scanner` must remain off so only the explicitly registered `ToolCallbackProvider` defines MCP discovery.

- [ ] **Step 7: Run context and real-protocol tests until green**

```powershell
.\mvnw.cmd -Dtest=SmartParkApplicationTest,McpEnabledContextTest,McpProtocolIntegrationTest test
.\mvnw.cmd -Dtest=*Mcp*Test test
```

Expected: the default context has no provider, the enabled context exposes exactly three tools, and a real stateless Streamable HTTP client can list and call them through `/mcp`.

- [ ] **Step 8: Commit the server-wiring slice**

```powershell
git add -- 'pom.xml' 'src/main/resources/application.yml' 'src/main/java/com/example/smartpark/adapter/mcp/SmartParkMcpConfiguration.java' 'src/test/java/com/example/smartpark/SmartParkApplicationTest.java' 'src/test/java/com/example/smartpark/adapter/mcp/McpEnabledContextTest.java' 'src/test/java/com/example/smartpark/adapter/mcp/McpProtocolIntegrationTest.java'
git commit -m "feat: expose readonly tools over MCP"
```

### Task 3: Document and manually verify the MCP client demo

**Files:**

- Modify: `README.md`

- [ ] **Step 1: Add a README section before `技术与场景说明`**

Name the section `只读 MCP 工具生态演示`. State clearly:

- MCP is disabled by default and is intended only for a trusted local demo because this slice adds no authentication or network boundary.
- No model API key is needed when DashScope is disabled; the three MCP tools query Mock park adapters directly through application Ports.
- The server exposes only three read-only tools and no Resources, Prompts, completion, mutation, workflow, or device-control capability.
- Spring AI 1.1.2 encodes each Java result envelope as JSON inside MCP `TextContent`; clients should parse that JSON rather than expect `structuredContent`.

Add this Windows PowerShell startup sequence:

```powershell
$env:SPRING_AI_DASHSCOPE_ENABLED='false'
$env:SMARTPARK_MCP_ENABLED='true'
.\mvnw.cmd spring-boot:run
```

Add cleanup commands in a separate terminal step:

```powershell
Remove-Item Env:SPRING_AI_DASHSCOPE_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:SMARTPARK_MCP_ENABLED -ErrorAction SilentlyContinue
```

- [ ] **Step 2: Document the exact public tool contract**

Add a compact table with these exact names and inputs:

| Tool | Inputs | Safe output |
|---|---|---|
| `smartpark_lookup_alert` | `alertId` | IDs, classification, risk hint, occurrence time |
| `smartpark_lookup_energy` | `meterId` | reading, baseline, peak demand, variance |
| `smartpark_search_knowledge` | `query`, `domain` | up to five metadata-only matches |

State the valid knowledge domains from the source enum, and use the current Mock fixture IDs in all examples rather than inventing IDs.

- [ ] **Step 3: Add reproducible MCP Inspector commands**

Document discovery:

```powershell
npx.cmd -y @modelcontextprotocol/inspector --cli http://127.0.0.1:8080/mcp --transport http --method tools/list
```

Document one command for each tool using the Inspector's `--method tools/call`, `--tool-name`, and repeated `--tool-arg key=value` syntax. Use:

- `alertId=ALT-ENERGY-001`
- the actual meter ID from `MockParkFixture`
- a short Chinese query plus one actual `KnowledgeDomain` enum value

Also document the interactive Inspector launcher:

```powershell
npx.cmd -y @modelcontextprotocol/inspector --web --server-url http://127.0.0.1:8080/mcp --transport http
```

- [ ] **Step 4: Add Codex connection instructions without mutating the user's global config**

Document the CLI workflow:

```powershell
codex mcp add smart-park --url http://127.0.0.1:8080/mcp
codex mcp get smart-park
codex mcp remove smart-park
```

Also document the equivalent TOML snippet:

```toml
[mcp_servers.smart-park]
url = "http://127.0.0.1:8080/mcp"
```

Do not execute `codex mcp add` during implementation. It changes user-level configuration and is outside this repository change. If the local WindowsApps `codex` executable remains inaccessible, verify syntax against current official OpenAI documentation and report that local CLI verification limitation explicitly.

- [ ] **Step 5: Start the server and manually run the Inspector smoke test**

With the two environment variables set, start the application in a background terminal. Run `tools/list` and all three `tools/call` examples. Confirm:

- discovery returns only the three approved names;
- successful output contains the safe notice and allowlisted metadata;
- invalid input returns a fixed error envelope and never reflects the raw input;
- no command exposes knowledge content, alert summary/evidence, identity data, mutation, or device control.

Stop the application cleanly after the smoke test and remove the two process-local environment variables.

- [ ] **Step 6: Commit the demo documentation**

```powershell
git add -- 'README.md'
git commit -m "docs: add readonly MCP demo"
```

### Task 4: Run the full regression and final contract audit

**Files:**

- Verify all files changed in Tasks 1-3
- Modify only a directly related file if a regression exposes a root-cause defect

- [ ] **Step 1: Run the complete backend test suite**

```powershell
.\mvnw.cmd test
```

Expected: all existing and new tests pass. Do not weaken an existing test or disable auto-configuration to hide an MCP collision.

- [ ] **Step 2: Verify the production package**

```powershell
.\mvnw.cmd package -DskipTests
```

Expected: the executable Spring Boot artifact packages successfully with the MCP starter present and MCP still disabled by default.

- [ ] **Step 3: Run the UI regression checks**

```powershell
Set-Location -LiteralPath 'ui'
npm.cmd run typecheck
npm.cmd run build
Set-Location -LiteralPath '..'
```

Expected: the unchanged UI continues to typecheck and build.

- [ ] **Step 4: Audit the final repository state**

```powershell
git diff --check
git status --short
git log -5 --oneline
```

Review the implementation against every requirement in the design spec:

- inbound MCP server only;
- stateless Streamable HTTP at `/mcp`;
- default off, local trusted demo, no auth claim;
- exactly three explicit read-only tools;
- Ports are the source of truth;
- fixed errors with no input reflection;
- metadata-only alert and knowledge output;
- mock energy comparison output;
- annotation scanning and non-tool capabilities disabled;
- unit, leakage, architecture, context, protocol, manual-client, backend, package, and UI verification all recorded separately.

If any check fails, first add or tighten the smallest focused regression test that reproduces the root cause, then make the narrowest in-scope fix and rerun the failed command plus its surrounding suite. Commit such a fix separately with a message that names the actual defect.
