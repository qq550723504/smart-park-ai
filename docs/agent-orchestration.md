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
safe summaries, state, evidence references, child run IDs and orchestration
trace events. `SMARTPARK_ORCHESTRATION_STATE_FILE` selects the path. Compose
mounts `/var/lib/smartpark/orchestration` on the `orchestration-state` named
volume.

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

## Idempotency and concurrency

`POST /api/orchestrations/runs` requires `Idempotency-Key`. The durable store
maps the key to a request fingerprint (definition, role and normalized input):

- same key + same request returns the existing run;
- same key + different request returns `409`;
- refresh/reconnect uses `GET` and never starts another run;
- child references are persisted before awaiting completion, allowing cancel to
  invoke the existing child abort seam;
- cancellation is persisted before the child is interrupted, so late results
  cannot overwrite `CANCELLED`;
- duplicate approval observation sees an already terminal step/run and is a
  no-op.

## API

- `POST /api/orchestrations/runs` — start or idempotently replay a run.
- `GET /api/orchestrations/runs/{runId}` — read the complete safe run projection.
- `POST /api/orchestrations/runs/{runId}/cancel` — cancel future steps and abort
  the current Operations/Collaboration child when supported.
- `GET /api/executions/{runId}/events` — existing Execution Trace SSE endpoint.
- `POST /api/workflows/{workflowId}/approval` — existing Human Approval API;
  orchestration observes the child workflow result and resumes.

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
