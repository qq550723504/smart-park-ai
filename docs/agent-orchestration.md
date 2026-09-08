# Agent Orchestration

Issue #59 adds one bounded orchestration definition: `JOINT_ANOMALY_ASSESSMENT`
(园区异常联合研判). It is an application-level coordinator, not a new Agent or
workflow engine.

```text
OrchestrationService
        |
        +-- OperationsAnalysisService (existing run)
        +-- EnergyTimeSeriesService (existing evidence query)
        +-- ExpertCollaborationService (existing run)
        +-- SecurityIncidentService (existing, role-gated evidence)
        +-- AlertWorkflow (existing run and Human Approval)
        |
        +-- OrchestrationRunStore + existing Execution Trace contract
```

The orchestrator stores child run references and safe evidence references. It
does not copy child state machines, SQL, provider responses or domain logic.
Customer Service is intentionally excluded because visitor/service requests are
not an anomaly-assessment step.

## Step matrix

| Step | Existing capability | Required | Condition |
| --- | --- | --- | --- |
| `collect-context` | supplied alert/building context | yes | always |
| `operations-analysis` | Operations Analysis | yes | always |
| `energy-time-series` | Energy Time Series | no | `energyRelated` and live capability |
| `expert-collaboration` | Expert Collaboration | no | `crossDomain` and live capability |
| `security-review` | Security Incident | no | `securityRelated`, live capability, `APPROVER/ADMIN` |
| `alert-workflow` | Alert Workflow | no | `requestAction`, alert id, live capability, permitted role |
| `final-summary` | orchestration projection | yes | required steps still viable |

Every step obtains a fresh capability snapshot immediately before it starts.
An optional step that is not applicable is `SKIPPED` without degrading the run.
An optional step requested by the scenario but unavailable/unauthorized is
`SKIPPED` with a partial reason. An optional execution failure is `FAILED` and
the run becomes `PARTIAL`. A required unavailable or failed step is `BLOCKED`
and the run becomes `FAILED`.

## State machines

Run states:

```text
RUNNING -> COMPLETED
RUNNING -> PARTIAL
RUNNING -> FAILED
RUNNING -> CANCELLED
RUNNING -> WAITING_APPROVAL -> RUNNING -> COMPLETED/PARTIAL
```

Step states:

```text
PENDING -> RUNNING -> COMPLETED
PENDING -> SKIPPED
PENDING/RUNNING -> BLOCKED/FAILED/CANCELLED
RUNNING -> WAITING_APPROVAL -> COMPLETED/FAILED
```

The orchestration trace uses `runId == traceId` and records `RUN_STARTED`,
`STEP_STARTED`, `STEP_COMPLETED`, `STEP_SKIPPED`, `STEP_FAILED`,
`WAITING_APPROVAL`, `APPROVAL_RESUMED`, and one terminal run event. Child runs
keep their own trace IDs; the orchestration result links them without copying
their internal events.

## Persistence and recovery

`FileOrchestrationRunStore` atomically replaces a JSON snapshot containing only
safe summaries, state, evidence/source references, child run IDs and orchestration
trace events. `SMARTPARK_ORCHESTRATION_STATE_FILE` selects the path. Compose
mounts `/var/lib/smartpark/orchestration` on the `orchestration-state` named
volume. Storage is bounded by `SMARTPARK_ORCHESTRATION_MAX_RETAINED_RUNS`
(default `200`) and `SMARTPARK_ORCHESTRATION_MAX_ACTIVE_RUNS` (default `8`).
Each durable run is capped by `SMARTPARK_ORCHESTRATION_MAX_RUN_BYTES` (default
`131072`), and request identifiers are validated before admission.
Admission first preserves idempotent replay, then rejects excess active work
with `429`; when retained capacity is full it atomically compacts the oldest
terminal runs and their idempotency keys. Active runs are never evicted.

Browser refresh/reconnect loads the saved run and resumes real polling. After a
backend restart, completed runs and their orchestration trace are rehydrated.
For a non-terminal child run, the coordinator never guesses that work
succeeded: a lost in-memory child is recorded as failed/blocked and recovery
continues only where safe.

The current Alert Workflow still uses Spring AI Alibaba `MemorySaver` and an
in-memory `WorkflowExecutionStore`. Therefore an approval pause survives browser
refresh in the same backend process, but its graph checkpoint cannot resume
after that process is replaced. Orchestration recovery reports this limitation
fail-closed; replacing the existing workflow checkpoint store is a separate
architecture change, not duplicated inside the orchestrator.

Each orchestration starts an owned Alert Workflow execution. It reuses the
existing graph, agents, approval and work-order logic, but never attaches two
parents to the same mutable child run. The ordinary Alert Workflow API keeps
its existing alert-level idempotency independently. This one-parent/one-child
ownership is what makes cancellation and approval deadlines deterministic.
Before admitting a requested action with an explicit building scope, the
orchestrator resolves the alert through the existing `AlertPort` and rejects the
request unless the alert belongs to one of those buildings.

## Idempotency and concurrency

`POST /api/orchestrations/runs` requires `Idempotency-Key`. The durable store
maps the key to a request fingerprint (definition, role and normalized input):

- same key + same request returns the existing run;
- same key + different request returns `409`;
- refresh/reconnect uses `GET` and never starts another run;
- child references are persisted before awaiting completion, allowing cancel to
  invoke the existing child abort seam;
- access to the singleton Operations Analysis runner is serialized; a busy
  direct analysis makes orchestration wait instead of permanently failing, and
  cancellation stops the wait before a child is admitted;
- Operations/Collaboration cancellation is persisted before the child is
  interrupted, so late results cannot overwrite `CANCELLED`; an Alert Workflow
  waiting for approval is first terminalized at its work-order side-effect
  boundary, and only then is the parent reported `CANCELLED`;
- duplicate approval observation sees an already terminal step/run and is a
  no-op.

Human approval is persisted as its typed decision (`APPROVED`, `REJECTED`, or
`null` when no approval occurred), separate from localized display summaries.
Terminal run locks are reference-counted and removed when their last user exits.
If retention compacts a run while its SSE projection is still cached, the trace
archive treats it as unknown rather than allowing the cache to bypass role
authorization. The in-memory replay registry also retains at most 512 runs,
evicts the oldest terminal histories first, and refuses new histories when all
slots are active rather than allowing heap growth without bound. A new or
idempotently replayed orchestration propagates that admission failure instead of
returning a run whose trace cannot be opened; only an exact event already present
in the projection is treated as a harmless duplicate.

The UI exposes the launch action only when the required Operations Analysis
capability is reported available; the backend independently rechecks that
capability before the required step starts.

## API

- `POST /api/orchestrations/runs` — start or idempotently replay a run.
- `GET /api/orchestrations/runs/{runId}` — read the complete safe run projection.
- `POST /api/orchestrations/runs/{runId}/cancel` — cancel future steps and abort
  the current Operations/Collaboration child or terminalize a waiting Alert
  Workflow before returning cancellation.
- `GET /api/executions/{runId}/events` — existing Execution Trace SSE endpoint;
  orchestration traces require the owning `X-Demo-Role` (or `ADMIN`). Native
  browser `EventSource` clients may send the same demo role as the `role` query
  parameter because that API cannot set a custom request header.
- `POST /api/workflows/{workflowId}/approval` — existing Human Approval API;
  orchestration observes the child workflow result and resumes.
- `POST /api/orchestrations/runs/maintenance/reconcile-approvals` — `ADMIN`-only
  immediate reconciliation and expired-wait cleanup.

Waiting approvals carry a durable deadline controlled by
`SMARTPARK_ORCHESTRATION_APPROVAL_TIMEOUT_SECONDS` (default `900`). A background
reconciliation sweep runs every
`SMARTPARK_ORCHESTRATION_MAINTENANCE_INTERVAL_SECONDS` (default `30`). Expired
waits are enforced atomically by the child Alert Workflow before the parent is
terminalized: decisions received before the deadline remain authoritative,
late decisions cannot create a work order, and active-run capacity is released.

The start/get/cancel APIs reuse `X-Demo-Role`. `CUSTOMER_AGENT` cannot start an
operations orchestration; security evidence remains limited to
`APPROVER/ADMIN`. A non-admin role cannot read a run created under a different
role.

## Truthfulness and remaining NOT_READY

There is no frontend fake progress, static Agent result, duplicate SQL query,
or LLM assertion that a tool succeeded. Final conclusions are assembled only
from completed step output and evidence references. `PARTIAL` always carries
the missing-domain reasons.

Still out of scope and `NOT_READY`:

- vibration/temperature telemetry and health scoring (#60);
- predictive-maintenance models;
- new fire/smoke, crowding, perimeter, post-absence and false-positive security
  semantics (#62);
- cross-process recovery of the existing in-memory Alert Workflow checkpoint;
- report history/download.
