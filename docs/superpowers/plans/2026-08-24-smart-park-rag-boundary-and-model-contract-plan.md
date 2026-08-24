# Smart Park RAG Boundary and Model Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent cross-domain knowledge retrieval and make DashScope customer answers obey an explicit, injection-resistant structured-output contract.

**Architecture:** Add an explicit `KnowledgeDomain` to knowledge documents and retrieval calls. Mock and RAG adapters isolate `CUSTOMER_SERVICE` and `ALERT_OPERATIONS` corpora, while the existing administrative capability manages domain-bearing documents. Centralize the customer-answer contract so both the prompt and parser use the same model-selectable reasons and citation rules; keep retrieval failures deterministic and outside the model contract.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, JUnit 5/AssertJ, Vue 3/TypeScript.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-rag-boundary-and-model-contract-design.md`

## Global Constraints

- Keep `mock` mode offline and preserve the existing safe human-handoff path.
- Do not add a fallback or implicit domain to production retrieval.
- `RETRIEVAL_UNAVAILABLE` remains a deterministic workflow reason and is never model-selectable.
- Do not log or expose full prompts, model output, or knowledge正文; customer responses expose citation metadata only.
- Preserve unrelated user work in `C:\Users\Henry\code\springaialibaba`; all implementation occurs in `C:\Users\Henry\code\springaialibaba\.worktrees\pr4-root-fixes`.
- Run each targeted test after its implementation step, then the full Maven suite, UI build, and `git diff --check`.

### Task 1: Introduce the explicit knowledge-domain contract

**Files:**
- Create: `src/main/java/com/example/smartpark/model/common/KnowledgeDomain.java`
- Modify: `src/main/java/com/example/smartpark/model/common/KnowledgeDocument.java`
- Modify: `src/main/java/com/example/smartpark/port/knowledge/KnowledgePort.java`
- Modify: `src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java`
- Modify: `src/main/java/com/example/smartpark/workflow/AlertWorkflowNodes.java`
- Modify: `src/main/java/com/example/smartpark/tool/knowledge/ParkKnowledgeTool.java`
- Modify: all Java production/test `new KnowledgeDocument(...)` call sites found by `rg`
- Test: `src/test/java/com/example/smartpark/model/KnowledgeDocumentTest.java`

**Interfaces:**
- `KnowledgeDomain` produces exactly `CUSTOMER_SERVICE` and `ALERT_OPERATIONS`.
- `KnowledgeDocument` produces `domain()` and rejects a null domain.
- `KnowledgePort` consumes `(KnowledgeDomain domain, String query)` for both `search` and `rankedSearch`.
- Customer workflow passes `KnowledgeDomain.CUSTOMER_SERVICE`.
- Alert workflow and `ParkKnowledgeTool` pass `KnowledgeDomain.ALERT_OPERATIONS`.

- [ ] **Step 1: Write the failing domain-validation test**

```java
@Test
void knowledgeDocumentsRequireAnExplicitDomain() {
    assertThatThrownBy(() -> new KnowledgeDocument(
            "KD-1", null, "Title", "body", List.of("tag"), Instant.EPOCH))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("domain");
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the domain constructor/member is absent**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=KnowledgeDocumentTest test"`

Expected: compilation failure mentioning the missing `KnowledgeDomain` type or six-argument `KnowledgeDocument` constructor.

- [ ] **Step 3: Add the domain type and migrate the application contract**

Add:

```java
public enum KnowledgeDomain { CUSTOMER_SERVICE, ALERT_OPERATIONS }
```

Change the record to `(String id, KnowledgeDomain domain, String title, String content, List<String> tags, Instant updatedAt)` and retain the existing null/list validation. Change the port methods to:

```java
List<KnowledgeDocument> search(KnowledgeDomain domain, String query);
default List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
    return search(domain, query).stream()
            .map(document -> new KnowledgeMatch(document, 1.0))
            .toList();
}
```

Update every constructor call and caller so no production path uses an unscoped search. Assign existing operational documents to `ALERT_OPERATIONS` and existing customer guides to `CUSTOMER_SERVICE`.

- [ ] **Step 4: Run the focused model, compile, and existing workflow tests**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=KnowledgeDocumentTest,CustomerServiceWorkflowTest,AlertWorkflowTest,ParkToolsTest test"`

Expected: PASS with 0 failures and 0 errors.

- [ ] **Step 5: Commit the contract migration**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: make knowledge retrieval domain-aware"
```

### Task 2: Isolate Mock and RAG corpora and restore alert runbooks

**Files:**
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockParkDataStore.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockKnowledgeAdapter.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapter.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagSeedKnowledgeConfiguration.java`
- Create: `src/main/resources/knowledge/KB-HVAC-001.md`
- Create: `src/main/resources/knowledge/KB-POWER-001.md`
- Create: `src/main/resources/knowledge/KB-ACCESS-001.md`
- Create: `src/main/resources/knowledge/KB-PUMP-001.md`
- Modify: `src/test/java/com/example/smartpark/adapter/mock/MockParkDataStoreTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapterTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/RagSeedKnowledgeConfigurationTest.java`
- Modify: `src/test/java/com/example/smartpark/integration/RagModeContextTest.java`

**Interfaces:**
- `MockKnowledgeAdapter.search(domain, query)` delegates to a domain-filtered store search.
- `RagKnowledgeAdapter` consumes `Map<KnowledgeDomain, VectorStore>` and routes save/search/setActive to the document's domain index.
- `RagKnowledgeConfiguration` produces one `SimpleVectorStore` per `KnowledgeDomain` using the same `EmbeddingModel`.

- [ ] **Step 1: Write failing domain-isolation tests**

Add assertions that an alert query returns an operational document but not a customer guide, and that a customer query returns a customer guide but not an alert runbook. In the RAG context test, assert both domain seed IDs are present and queryable through their respective domains.

```java
assertThat(adapter.search(KnowledgeDomain.ALERT_OPERATIONS, "temperature"))
        .extracting(KnowledgeDocument::id).contains("KB-HVAC-001");
assertThat(adapter.search(KnowledgeDomain.CUSTOMER_SERVICE, "temperature"))
        .extracting(KnowledgeDocument::id).doesNotContain("KB-HVAC-001");
```

- [ ] **Step 2: Run the focused adapter/integration tests and verify they fail**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=MockParkDataStoreTest,RagKnowledgeAdapterTest,RagSeedKnowledgeConfigurationTest,RagModeContextTest test"`

Expected: compilation or assertion failures because the adapter has no domain-aware index and the RAG seed set has no operational documents.

- [ ] **Step 3: Implement domain filtering and separate vector indexes**

Filter Mock documents by `document.domain()`. Change RAG metadata to keep one `ManagedDocument` map for administration and an `EnumMap<KnowledgeDomain, VectorStore>` for vectors. During construction, validate every seed document has a configured domain store and call `save`; during search, select the requested store before applying the existing similarity threshold and active-document checks. Keep the existing replacement-before-metadata publication behavior.

Add four alert-operational seed resources covering HVAC temperature, power emergency, access anomaly, and pump/water leak handling. Mark the existing energy anomaly seed as operational and keep the tenant energy guide as customer-service knowledge.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=MockParkDataStoreTest,RagKnowledgeAdapterTest,RagSeedKnowledgeConfigurationTest,RagModeContextTest test"`

Expected: PASS with the two corpora isolated and all operational seed IDs present.

- [ ] **Step 5: Commit the corpus/index fix**

```powershell
git add src/main/java src/main/resources/knowledge src/test/java
git commit -m "fix: isolate customer and alert rag knowledge"
```

### Task 3: Make administration and public metadata domain-aware

**Files:**
- Modify: `src/main/java/com/example/smartpark/web/KnowledgeAdminController.java`
- Modify: `ui/src/types/workflow.ts`
- Modify: `ui/src/components/DemoConsole.vue`
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Test: `src/test/java/com/example/smartpark/web/KnowledgeAdminControllerTest.java`

**Interfaces:**
- `KnowledgeCreateRequest` consumes a required `KnowledgeDomain domain`.
- `KnowledgeMetadataResponse` produces `domain` alongside ID/title/tags/active metadata.
- The admin UI renders the domain label and sends/reads the expanded metadata without exposing content.

- [ ] **Step 1: Write the failing controller contract test**

Post a valid admin document with `domain: "CUSTOMER_SERVICE"`, assert the response contains that domain, and assert a request without `domain` is rejected with HTTP 400. Add a UI type assertion through the TypeScript build by adding the required `domain` property to `KnowledgeMetadata` and rendering it in the admin list.

- [ ] **Step 2: Run the focused controller test and UI typecheck/build to verify failure**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=KnowledgeAdminControllerTest test"` and `npm.cmd --prefix ui run build`.

Expected: the controller test fails because the request/response do not carry a domain; the UI build fails until all type consumers are updated.

- [ ] **Step 3: Implement domain-aware admin DTOs and UI display**

Pass `request.domain()` into `KnowledgeDocument`, return `document.domain()` from metadata, add `domain` to the TypeScript interface, and render a short domain label next to each document ID. Update documentation to state that alert and customer knowledge are separate corpora and managed documents must declare a domain.

- [ ] **Step 4: Run focused backend and frontend verification**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=KnowledgeAdminControllerTest,CustomerServiceControllerTest test"` and `npm.cmd --prefix ui run build`.

Expected: PASS with HTTP validation and TypeScript compilation clean.

- [ ] **Step 5: Commit the administration boundary**

```powershell
git add src/main/java/com/example/smartpark/web ui README.md docs/architecture.md src/test/java/com/example/smartpark/web
git commit -m "feat: expose knowledge domains in administration"
```

### Task 4: Centralize the customer-answer contract and harden the DashScope prompt

**Files:**
- Modify: `src/main/java/com/example/smartpark/model/customer/CustomerAnswer.java`
- Create: `src/main/java/com/example/smartpark/adapter/rag/CustomerAnswerContract.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/StructuredCustomerAnswerParser.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/DashScopeCustomerAnswerAdapter.java`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/StructuredCustomerAnswerParserTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/DashScopeCustomerAnswerAdapterTest.java`

**Interfaces:**
- `CustomerAnswerContract.modelReasons()` produces exactly `SUPPORTED`, `INSUFFICIENT_EVIDENCE`, and `POLICY_LIMIT`.
- `CustomerAnswerContract.systemMessage()` produces the immutable safety and JSON contract instructions.
- `CustomerAnswerContract.userMessage(intent, question, evidence)` produces delimited untrusted data only.
- `StructuredCustomerAnswerParser.parse` rejects internal retrieval reasons and all cross-field/citation violations.

- [ ] **Step 1: Write failing parser and prompt tests**

Add parser cases for `RETRIEVAL_UNAVAILABLE` and `needsHuman=true` with a non-empty citation list. Add a DashScope adapter assertion that `model.lastPrompt().getInstructions()` contains a `SYSTEM` instruction with the safety policy, a separate `USER` instruction containing delimited evidence, and the exact allowed reason names.

```java
assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse(
        "{\"answer\":\"暂不可用\",\"needsHuman\":true,\"reason\":\"RETRIEVAL_UNAVAILABLE\",\"citationIds\":[]}", evidence))
        .isInstanceOf(ModelOutputException.class);
```

- [ ] **Step 2: Run the focused parser/prompt tests and verify they fail**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=StructuredCustomerAnswerParserTest,DashScopeCustomerAnswerAdapterTest test"`

Expected: parser accepts the internal reason or the prompt lacks a separate system instruction, proving the tests cover the outstanding review findings.

- [ ] **Step 3: Implement the shared contract and system/user message split**

Derive the model-reason list from `CustomerAnswer.Reason` or a single contract-owned immutable set, but do not expose `RETRIEVAL_UNAVAILABLE` in the model set. Make the system message specify exact field names, uppercase reason values, citation origin, and cross-field rules. Build the user message with labels such as `<intent>`, `<question>`, and `<evidence>` and state that their contents are untrusted data. Call `chatClient.prompt().system(systemMessage).user(userMessage)` and keep the existing parser after model generation.

In the parser, validate the model reason against the contract set, require empty citations for human handoff, require unique non-empty evidence citations for supported answers, and keep all parser failures wrapped as `ModelOutputException`.

- [ ] **Step 4: Run focused tests and the customer handoff regression tests**

Run: `cmd.exe /d /c ".\mvnw.cmd -B -Dtest=StructuredCustomerAnswerParserTest,DashScopeCustomerAnswerAdapterTest,CustomerServiceAnswerFailureTest,CustomerServiceWorkflowTest test"`

Expected: PASS; invalid model output still produces the workflow's safe handoff and no model text escapes.

- [ ] **Step 5: Commit the model safety/contract fix**

```powershell
git add src/main/java/com/example/smartpark/model/customer src/main/java/com/example/smartpark/adapter/rag src/test/java/com/example/smartpark/adapter/rag src/test/java/com/example/smartpark/workflow
git commit -m "fix: enforce safe structured customer answers"
```

### Task 5: Full verification and handoff

**Files:**
- Modify only files required by failing tests or documentation review; do not perform unrelated cleanup.

- [ ] **Step 1: Run architecture/import and complete backend verification**

Run: `cmd.exe /d /c ".\mvnw.cmd -B test"`

Expected: all tests pass, with only the existing conditional DashScope skip when no credentials are configured.

- [ ] **Step 2: Run the frontend build and whitespace verification**

Run: `npm.cmd --prefix ui run build` and `git diff --check`.

Expected: exit code 0 for both commands and no whitespace errors.

- [ ] **Step 3: Review the final diff against the design**

Run: `git diff --stat 720a3a2..HEAD` and `git status --short`.

Confirm the diff contains explicit domain selection in every retrieval caller, both RAG corpora, required admin domain validation, system/user prompt separation, and parser restrictions for model-selectable reasons. Confirm no files outside the isolated worktree were changed.

- [ ] **Step 4: Commit any final test/documentation-only adjustments**

```powershell
git add src ui README.md docs
git commit -m "test: verify rag domain and answer contract boundaries"
```

- [ ] **Step 5: Report evidence separately**

Report the isolated branch/worktree, commit list, test/build commands and actual counts, remaining conditional skips, and the fact that no deployment, PR mutation, or merge was performed.
