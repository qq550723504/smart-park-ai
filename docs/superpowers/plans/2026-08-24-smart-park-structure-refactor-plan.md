# Smart Park Structure Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the adapter-to-Web dependency, separate customer session and ticket storage behind ports, and synchronize project documentation without changing current Mock behavior or HTTP contracts.

**Architecture:** Keep `CustomerServiceWorkflow` as the application orchestrator. Move demo fault state to `com.example.smartpark.demo`, introduce `CustomerSessionStore` and `CustomerTicketPort` in the port layer, and provide in-memory implementations under `adapter.mock`. Controllers and public DTOs remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.5.8, JUnit 5, AssertJ, Maven Wrapper, Vue/Vite.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-structure-refactor-design.md`

## Global Constraints

- Preserve all existing HTTP paths, JSON fields, Mock fixtures, workflow statuses, and customer-service behavior.
- Do not add PostgreSQL, Flyway, Spring Security, vector search, real park integrations, or new business scenes.
- Port packages cannot depend on Spring Web, Mock adapters, or database-specific classes.
- Do not expose original customer questions, raw security media, identity records, or provider secrets in new logs or DTOs.
- Every production-code change starts with a failing test and is followed by a focused test run.
- Keep commits scoped: fault-boundary refactor, customer storage ports, workflow wiring, and documentation/verification.

### Task 1: Move demo fault injection out of the Web package

**Files:**
- Create: `src/main/java/com/example/smartpark/demo/DemoFaultInjector.java`
- Create: `src/test/java/com/example/smartpark/demo/DemoFaultInjectorTest.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockKnowledgeAdapter.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockParkConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/web/DemoFaultController.java`
- Create or modify: `src/test/java/com/example/smartpark/architecture/AdapterDependencyTest.java`

**Interfaces:**

Produce `com.example.smartpark.demo.DemoFaultInjector` with the existing `FaultPoint` values and one-shot `inject`/ `failIfRequested` behavior. Consumers import the demo type; no production class under `com.example.smartpark.adapter` imports `com.example.smartpark.web`.

- [ ] **Step 1: Write the failing dependency test**

Add a source-level architecture test that walks `src/main/java/com/example/smartpark/adapter`, reads Java files as UTF-8, and fails if any file contains `com.example.smartpark.web`. The assertion must include the offending path.

~~~java
@Test
void adaptersDoNotDependOnWebLayer() throws IOException {
    Path root = Paths.get("src/main/java/com/example/smartpark/adapter");
    try (Stream<Path> files = Files.walk(root)) {
        files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
            try {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                assertThat(source).as("adapter source %s", path)
                        .doesNotContain("com.example.smartpark.web");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }
}
~~~

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.architecture.AdapterDependencyTest test
~~~

Expected result: FAIL because `MockKnowledgeAdapter.java` and `MockParkConfiguration.java` still contain the Web package reference.

- [ ] **Step 3: Add the demo-layer implementation**

Move the existing `DemoFaultInjector` implementation to `com.example.smartpark.demo.DemoFaultInjector`. Keep its `AtomicReference`, `FaultPoint.KNOWLEDGE_SEARCH`, `inject(Fault)`, and `failIfRequested(FaultPoint)` semantics unchanged. Delete the old Web-package class only after all imports are changed.

- [ ] **Step 4: Update consumers and add the one-shot behavior test**

Change imports and constructor parameter types in `MockKnowledgeAdapter`, `MockParkConfiguration`, and `DemoFaultController`. Add a test that injects one knowledge-search fault, verifies the first consume throws, and verifies the second consume does not throw.

~~~java
DemoFaultInjector injector = new DemoFaultInjector();
injector.inject(new DemoFaultInjector.Fault(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH));
assertThatThrownBy(() -> injector.failIfRequested(
        DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH))
        .isInstanceOf(IllegalStateException.class);
assertThatCode(() -> injector.failIfRequested(
        DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH))
        .doesNotThrowAnyException();
~~~

- [ ] **Step 5: Run focused tests and commit**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.demo.DemoFaultInjectorTest,com.example.smartpark.architecture.AdapterDependencyTest,com.example.smartpark.adapter.mock.KnowledgeManagementTest test
~~~

Expected result: all focused tests pass.

~~~powershell
git add -- src/main/java/com/example/smartpark/demo src/main/java/com/example/smartpark/adapter/mock src/main/java/com/example/smartpark/web/DemoFaultController.java src/test/java/com/example/smartpark/demo src/test/java/com/example/smartpark/architecture
git commit -m "refactor: isolate demo fault injection from web"
~~~

### Task 2: Introduce customer session and ticket ports with in-memory adapters

**Files:**
- Create: `src/main/java/com/example/smartpark/port/customer/CustomerSessionStore.java`
- Create: `src/main/java/com/example/smartpark/port/customer/CustomerTicketPort.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/InMemoryCustomerSessionStore.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/InMemoryCustomerTicketAdapter.java`
- Create: `src/test/java/com/example/smartpark/adapter/mock/InMemoryCustomerSessionStoreTest.java`
- Create: `src/test/java/com/example/smartpark/adapter/mock/InMemoryCustomerTicketAdapterTest.java`
- Create: `src/test/java/com/example/smartpark/architecture/CustomerPortBoundaryTest.java`

**Interfaces:**

Create `CustomerSessionStore` without Spring types:

~~~java
public interface CustomerSessionStore {
    Optional<SessionSnapshot> find(String sessionId, Instant now);
    Optional<IdempotencyRecord> findIdempotency(String key, Instant now);
    SessionSnapshot create(String sessionId, CustomerServiceResult result,
                           List<CustomerConversation.Message> messages,
                           List<CustomerConversation.RetrievalTrace> retrievals,
                           Instant createdAt);
    SessionSnapshot update(SessionSnapshot snapshot);
    void rememberIdempotency(String key, String question, String sessionId, Instant createdAt);
    List<SessionSnapshot> withTickets(Instant now);
    int count(Instant now);

    record SessionSnapshot(
            String sessionId,
            CustomerServiceResult result,
            Instant createdAt,
            List<CustomerConversation.Message> messages,
            List<CustomerConversation.RetrievalTrace> retrievals) { }

    record IdempotencyRecord(String question, String sessionId, Instant createdAt) { }
}
~~~

Create `CustomerTicketPort` without Spring types:

~~~java
public interface CustomerTicketPort {
    CustomerTicket create(String sessionId, String intent,
                          String safeSummary, Instant createdAt);
    List<CustomerTicket> list();
    CustomerTicket update(String ticketId, CustomerTicketStatus nextStatus);
}
~~~

`InMemoryCustomerSessionStore` owns the existing TTL, capacity limit, insertion order, session map, and idempotency map. Its constructors support the current defaults of 10,000 sessions and 24 hours, plus a test constructor accepting `Clock`, maximum sessions, and TTL.

`InMemoryCustomerTicketAdapter` owns ticket ID sequencing and delegates legal status transitions to `CustomerTicket.transitionTo`. Unknown IDs throw `NoSuchElementException`; `list()` returns an immutable snapshot sorted by creation time.

- [ ] **Step 1: Write failing adapter tests**

Test real in-memory implementations for:

- saving and reading a session;
- appending multi-turn messages by updating a snapshot;
- expiry of sessions and idempotency records together;
- oldest-session capacity eviction;
- idempotency record reuse;
- ticket creation as `CS-0001`;
- legal ticket lifecycle transitions;
- unknown-ticket rejection.

The expiry test should have this shape:

~~~java
@Test
void sessionStoreExpiresSessionsAndIdempotencyTogether() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-23T02:00:00Z"));
    InMemoryCustomerSessionStore store =
            new InMemoryCustomerSessionStore(clock, 10, Duration.ofMinutes(5));
    store.create("cs-1", result("cs-1"), List.of(), List.of(), clock.instant());
    store.rememberIdempotency("request-1", "same question", "cs-1", clock.instant());

    clock.advance(Duration.ofMinutes(6));

    assertThat(store.find("cs-1", clock.instant())).isEmpty();
    assertThat(store.findIdempotency("request-1", clock.instant())).isEmpty();
}
~~~

- [ ] **Step 2: Run focused tests and verify the expected failure**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.adapter.mock.InMemoryCustomerSessionStoreTest,com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapterTest test
~~~

Expected result: test compilation fails because the new ports and adapters do not exist. Fix only test syntax or missing test fixtures; do not weaken assertions.

- [ ] **Step 3: Implement the port types and immutable snapshots**

Add both interfaces. Validate record components with null checks and immutable list copies. Keep the ports independent of Spring Web, Mock classes, and database types.

- [ ] **Step 4: Implement the session adapter minimally**

Use concurrent maps for sessions and idempotency records, a monotonic insertion sequence for deterministic eviction, and synchronized compound operations. On every read or write, remove entries whose `createdAt.plus(sessionTtl)` is before or equal to `now`. Capacity eviction removes the oldest session and its idempotency records.

- [ ] **Step 5: Implement the ticket adapter minimally**

Use an atomic sequence formatted as `CS-%04d`, retain tickets by ID, and implement `update` with `CustomerTicket.transitionTo`. Return a snapshot list from `list()`.

- [ ] **Step 6: Run focused tests, boundary checks, and commit**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.adapter.mock.InMemoryCustomerSessionStoreTest,com.example.smartpark.adapter.mock.InMemoryCustomerTicketAdapterTest,com.example.smartpark.architecture.CustomerPortBoundaryTest test
~~~

Expected result: all focused tests pass.

~~~powershell
git add -- src/main/java/com/example/smartpark/port/customer src/main/java/com/example/smartpark/adapter/mock/InMemoryCustomerSessionStore.java src/main/java/com/example/smartpark/adapter/mock/InMemoryCustomerTicketAdapter.java src/test/java/com/example/smartpark/adapter/mock src/test/java/com/example/smartpark/architecture/CustomerPortBoundaryTest.java
git commit -m "refactor: add customer storage ports"
~~~

### Task 3: Refactor CustomerServiceWorkflow to use the ports

**Files:**
- Modify: `src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java`
- Modify: `src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java`
- Modify: `src/test/java/com/example/smartpark/workflow/CustomerServiceWorkflowTest.java`
- Create: `src/test/java/com/example/smartpark/workflow/CustomerServiceWorkflowPortTest.java`
- Preserve and run: `src/test/java/com/example/smartpark/web/CustomerServiceControllerTest.java`

**Interfaces:**

Add a constructor used by tests and runtime wiring:

~~~java
CustomerServiceWorkflow(
        KnowledgePort knowledgePort,
        CustomerSessionStore sessionStore,
        CustomerTicketPort ticketPort,
        Clock clock,
        Supplier<String> sessionIds)
~~~

Keep `CustomerServiceWorkflow(KnowledgePort)` and existing package-private clock/session-ID constructors as compatibility constructors. They delegate to the in-memory ports; no map or sequence field remains in the workflow.

- [ ] **Step 1: Write a failing workflow port-usage test**

Create recording test implementations of both ports. Verify that a repair question invokes ticket creation and session creation, and that `updateTicket` invokes ticket update followed by session update.

~~~java
@Test
void workflowDelegatesTicketLifecycleAndSessionPersistenceToPorts() {
    RecordingSessionStore sessions = new RecordingSessionStore();
    RecordingTicketPort tickets = new RecordingTicketPort();
    CustomerServiceWorkflow workflow = new CustomerServiceWorkflow(
            new MockParkFixture().knowledge(), sessions, tickets,
            Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC),
            () -> "cs-port-001");

    CustomerServiceResult created = workflow.handle("A1 洗手间漏水，需要报修");
    workflow.updateTicket(created.ticket().id(), "ASSIGNED");

    assertThat(tickets.created).isTrue();
    assertThat(tickets.updated).isTrue();
    assertThat(sessions.created).isTrue();
    assertThat(sessions.updated).isTrue();
}
~~~

- [ ] **Step 2: Run the workflow port test and verify the expected failure**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.workflow.CustomerServiceWorkflowPortTest test
~~~

Expected result: compilation fails because `CustomerServiceWorkflow` has no constructor accepting the two ports.

- [ ] **Step 3: Replace workflow-owned collections with port fields**

Add only `KnowledgePort`, `CustomerSessionStore`, `CustomerTicketPort`, `Clock`, and `Supplier<String>` fields. Remove the workflow's ticket/session atomics and both concurrent maps.

Change `handle` to read idempotency records from `sessionStore`, create tickets through `ticketPort`, create session snapshots through `sessionStore`, and remember idempotency keys through the store. Preserve synchronized request methods and existing answer text.

- [ ] **Step 4: Refactor reply, query, and ticket update paths**

Change `reply`, `conversation`, `get`, `sessionCount`, and `tickets` to read through `sessionStore`. Change `updateTicket` to call `ticketPort.update`, load the corresponding session, replace its result ticket, and save the updated snapshot. Preserve human-handoff refusal, ticket sorting, and all existing status-transition errors.

- [ ] **Step 5: Wire the default in-memory adapters**

Update `CustomerServiceRuntimeConfiguration.customerServiceWorkflow` to inject `KnowledgePort`, `CustomerSessionStore`, and `CustomerTicketPort`. Add bean methods for the two in-memory adapters. The application context must contain exactly one bean of each port type.

- [ ] **Step 6: Update tests for custom clocks and limits**

Change expiration and capacity tests to construct `InMemoryCustomerSessionStore` with the mutable clock, limit, and TTL, then pass it to the port-aware workflow constructor. Keep assertions for parking, repair, unknown questions, idempotency, lifecycle, expiration, capacity, and concurrency unchanged.

- [ ] **Step 7: Run workflow and HTTP tests and commit**

Run:

~~~powershell
.\mvnw.cmd -B -Dtest=com.example.smartpark.workflow.CustomerServiceWorkflowTest,com.example.smartpark.workflow.CustomerServiceWorkflowPortTest,com.example.smartpark.web.CustomerServiceControllerTest,com.example.smartpark.web.OperationsMetricsTest test
~~~

Expected result: all tests pass with unchanged HTTP responses.

~~~powershell
git add -- src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java src/test/java/com/example/smartpark/workflow src/test/java/com/example/smartpark/web/CustomerServiceControllerTest.java
git commit -m "refactor: separate customer workflow storage"
~~~

### Task 4: Synchronize documentation and perform full verification

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`

**Interfaces:**

Documentation describes current endpoint groups, the new customer storage ports and Mock adapters, and the remaining production gaps.

- [ ] **Step 1: Locate stale documentation references**

Run:

~~~powershell
rg -n "API 一共四个端点|web\.DemoFaultInjector|model\.common.*ParkContext|CustomerServiceWorkflow.*内存|CustomerSessionStore|CustomerTicketPort" README.md docs/architecture.md src/main/java src/test/java
~~~

Record each stale reference and map it to the replacement section before editing.

- [ ] **Step 2: Update README**

Group actual endpoints under告警工作流、客服、知识管理、运营与审计、演示故障注入. State that customer service still uses deterministic Mock classification and keyword retrieval, and that the new ports are backed by in-memory adapters.

- [ ] **Step 3: Update architecture documentation**

Correct model package paths, add the customer session/ticket port-to-adapter relationship, and explain that this refactor creates replacement boundaries but does not provide persistence or a real agent system.

- [ ] **Step 4: Run full verification**

Run:

~~~powershell
git diff --check
rg -n "com\.example\.smartpark\.web" src/main/java/com/example/smartpark/adapter
rg -n "API 一共四个端点|web\.DemoFaultInjector|model\.common.*ParkContext" README.md docs/architecture.md
.\mvnw.cmd -B test
npm.cmd run build
git status --short
~~~

Expected results: the adapter search returns no Web imports; stale-document searches return no matches; Maven exits 0 with zero failures; the frontend build exits 0; and final status contains only changes from this plan before the documentation commit.

- [ ] **Step 5: Commit the documentation slice**

~~~powershell
git add -- README.md docs/architecture.md
git commit -m "docs: sync smart park architecture boundaries"
~~~

## Final Review Checklist

- [ ] Adapter production code has no Web dependency.
- [ ] `CustomerServiceWorkflow` has no session/ticket maps or ID sequences.
- [ ] Customer storage is accessed through `CustomerSessionStore` and `CustomerTicketPort`.
- [ ] Default Spring wiring provides one in-memory session store and one in-memory ticket adapter.
- [ ] Existing customer-service and alert behavior is covered by passing tests.
- [ ] README and architecture documentation match current endpoints and package paths.
- [ ] Maven tests and frontend build pass after the final documentation commit.
