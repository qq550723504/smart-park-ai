# Smart Park Alert Workflow

This project is a learning-oriented Spring AI Alibaba workflow for triaging a seeded park alert, collecting Mock park context, producing a structured diagnosis, applying a risk gate, pausing for human approval when required, creating an in-memory Mock work order, and streaming workflow events over SSE.

> Safety boundary: every park adapter in this repository is a `MockParkSystem` adapter. It reads seeded data and writes only in-memory Mock work orders. It does **not** inspect, switch, restart, isolate, or otherwise control a real device. Do not use this sample as an operational control system.

## Prerequisites

- Java 17 or newer (`pom.xml` targets Java 17)
- An internet-accessible DashScope account and API key only when you intentionally run the real chat model
- No system Maven installation is required; use the committed Maven Wrapper

The default configuration reads the key from the process environment variable `AI_DASHSCOPE_API_KEY`. Do not put a key in source control, this README, an `.env` file, command-line arguments, or shell history.

## Build and test

### Windows PowerShell

```powershell
java -version
.\mvnw.cmd --version
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
```

To start the application with a key supplied to the current PowerShell process without placing it in command history:

```powershell
$secureDashScopeKey = Read-Host 'DashScope API key' -AsSecureString
$env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureDashScopeKey).Password
.\mvnw.cmd spring-boot:run
```

After stopping the application with `Ctrl+C`, remove the process-scoped value:

```powershell
Remove-Item Env:AI_DASHSCOPE_API_KEY
Remove-Variable secureDashScopeKey
```

### macOS/Linux

```bash
java -version
./mvnw --version
./mvnw test
./mvnw package -DskipTests
```

In a Bash-compatible shell, read the key without echoing it or writing it into shell history, then start the application:

```bash
read -rsp 'DashScope API key: ' AI_DASHSCOPE_API_KEY && echo
export AI_DASHSCOPE_API_KEY
./mvnw spring-boot:run
```

After stopping the application with `Ctrl+C`:

```bash
unset AI_DASHSCOPE_API_KEY
```

Starting a workflow invokes the configured `qwen-plus` chat model and therefore requires the external provider and network. Unit and integration tests use a test double or disable DashScope; they do not require a real key or provider call.

## Seeded Mock alerts

`MockParkSystem` resets its in-memory data whenever the application starts and includes these two workflow entry points:

| Alert ID | Device | Seeded risk hint | Intended exercise |
| --- | --- | --- | --- |
| `ALT-TEMP-001` | `DEV-HVAC-001` | `LOW` | Temperature triage and HVAC knowledge lookup. It can still require approval when diagnosis risk is high, confidence is low, or evidence is missing. |
| `ALT-POWER-001` | `DEV-POWER-001` | `HIGH` | High-risk power diagnosis. If execution reaches the risk gate, it must pause for approval. |

All workflow state, events, approvals, and work orders are in memory. Restarting the application loses them.

## REST and SSE examples

The API currently has exactly four endpoints. It has no authentication, so expose it only on a trusted local development machine. Examples use `curl`; in Windows PowerShell, use `curl.exe` in place of `curl`.

### 1. Start a workflow

```bash
curl -X POST "http://localhost:8080/api/alerts/ALT-TEMP-001/workflows"
```

Use `ALT-POWER-001` instead to exercise the mandatory high-risk approval path. Save the returned `workflowId` for the remaining requests.

### 2. Read workflow status

```bash
curl "http://localhost:8080/api/workflows/replace-with-workflow-id"
```

Possible statuses are `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `REJECTED`, `FAILED`, and `WORK_ORDER_FAILED`. Public DTOs intentionally redact diagnosis, operator, work-order, and error details.

### 3. Approve or reject a paused workflow

Approval is valid only while the workflow is `WAITING_APPROVAL`. The required `idempotencyKey` makes an exact retry return the existing result and rejects reuse for a different decision.

```bash
curl -X POST "http://localhost:8080/api/workflows/replace-with-workflow-id/approval" -H "Content-Type: application/json" --data '{"decision":"APPROVE","reviewer":"operator-1","comment":"safe to dispatch Mock work order","idempotencyKey":"approval-request-001"}'
```

The JSON contract is:

```json
{
  "decision": "APPROVE",
  "reviewer": "operator-1",
  "comment": "safe to dispatch Mock work order",
  "idempotencyKey": "approval-request-001"
}
```

`decision` accepts `APPROVE` or `REJECT`. A Mock approval may create an in-memory Mock work order; it never authorizes or controls a real device.

### 4. Stream workflow events with SSE

```bash
curl -N -H "Accept: text/event-stream" "http://localhost:8080/api/workflows/replace-with-workflow-id/events"
```

The in-memory publisher replays events for that workflow and the stream ends after a `COMPLETED` or `FAILED` event. SSE payloads use a redacted public DTO and do not expose internal Graph state.

## Learning map

| Topic | Where to study | What the sample demonstrates |
| --- | --- | --- |
| `ChatModel` / `ChatClient` | `com.example.smartpark.agent` | `AlertTriageAgent` calls `ChatModel` directly; `AlertDiagnosisAgent` builds a `ChatClient` around the injected model. |
| Tool Calling | `com.example.smartpark.tool` and `AlertDiagnosisAgent` | `@Tool` methods are converted to callbacks and supplied to the diagnosis call. Diagnosis receives audited, read-only callbacks; work-order creation remains a deterministic workflow action. |
| Structured output | `AlertTriageAgent`, `AlertDiagnosisAgent`, and `PromptCatalog` | Prompts require JSON; agents parse it with Jackson, reject missing or extra fields, validate enums/ranges, and then construct typed records/domain objects. This sample does not hide validation behind an automatic output converter. |
| Graph and state | `com.example.smartpark.workflow.AlertWorkflow`, `AlertWorkflowNodes`, and `AlertWorkflowState` | A Spring AI Alibaba `StateGraph` is compiled into ordered and conditional nodes with explicit state keys and reducers. |
| Interrupt / resume | `AlertWorkflowNodes.HumanApprovalNode` and `AlertWorkflow.approve` | High-risk or uncertain execution emits interruption metadata, persists the in-memory execution, accepts operator feedback, and resumes the same Graph thread. |
| Risk gate | `AlertWorkflowNodes.RiskGate` | High risk, confidence below the threshold, or missing knowledge evidence routes to human approval; otherwise the workflow can create a Mock work order directly. |
| Idempotency | `ApprovalDecision`, `AlertWorkflow`, `WorkflowExecutionStore`, and `MockParkSystem` | Approval retries are keyed by `idempotencyKey`; workflow starts and Mock work-order writes also preserve workflow-scoped identity in memory. |
| SSE | `WorkflowEventPublisher`, `WorkflowEventController`, and `WebDtos.WorkflowEventDto` | Reactor `Flux` publishes replayable workflow events as redacted Spring `ServerSentEvent` records and closes on terminal events. |

## Deliberately deferred exercises

This first vertical slice is intentionally not production-ready. The following are later exercises, not features already provided here:

- **Embedding/RAG:** `MockParkSystem.search` is deterministic in-memory keyword matching. There is no embedding model, vector store, ingestion pipeline, or retrieval-augmented generation stack.
- **PostgreSQL checkpointing:** Graph executions, events, approvals, idempotency records, and work orders are held in process memory. There is no PostgreSQL-backed checkpoint or restart recovery.
- **Authentication and authorization:** the four HTTP endpoints have no identity, role, tenant, or approval-policy enforcement.
- **Real adapters:** `AlertPort`, `DevicePort`, `KnowledgePort`, and `WorkOrderPort` are extension boundaries, but only `MockParkSystem` is wired. Real park APIs, durable work orders, and device-control adapters are not implemented.
