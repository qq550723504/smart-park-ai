# Operations reporting

## Architecture

```text
OperationsDailyReport
        -> existing OperationsAnalysisService sections
        -> durable structured report snapshot
        -> history / detail API
        -> persisted Markdown artifact / download
```

`OperationsDailyReportService` remains the only report orchestrator. It executes the three server-owned
sections through `OperationsReportSectionRunner`, which calls the existing governed analytics service.
It does not duplicate metric definitions, SQL generation, SQL validation, query execution, or summaries.

The current sections are:

| Section | Metric | Source |
| --- | --- | --- |
| Energy baseline | `energy_deviation_pct` | `analytics.v_energy_hourly` through Operations Analytics |
| Parking utilization | `parking_utilization_pct` | `analytics.v_parking_daily` through Operations Analytics |
| Alert risk | `high_risk_alert_count` | `analytics.v_alert_fact` through Operations Analytics |

Device Health is not added to this fixed report template in this slice. Its truthful API remains available
independently, and predictive maintenance remains `NOT_READY`.

## Lifecycle

```text
REQUESTED -> GENERATING -> COMPLETED
                        -> PARTIAL
                        -> FAILED
```

Every section has its own `PENDING`, `RUNNING`, `COMPLETED`, `UNAVAILABLE`, or `FAILED` state. One
unavailable section and at least one completed section produces `PARTIAL`; no completed sections produces
`FAILED`. There is no synthetic replacement data and no progress percentage.

## Storage

The Analytics PostgreSQL login is deliberately read-only and is not used for report writes. Reports use the
same bounded, atomic file-store pattern established by orchestration, with an independent state file:

```text
./data/reports/reports.json
```

The complete JSON collection is written to a temporary file and atomically replaces the prior state. A
Docker named volume mounts `/var/lib/smartpark/reports`. Defaults bound the store to 200 retained reports,
one active report, 512 KiB per report, 256 KiB per artifact, and list pages of at most 50 items. Oldest
terminal reports are removed before admitting a new report; active reports are never evicted. If capacity
cannot be reclaimed, admission fails closed.

## Snapshot semantics

Historical reports are immutable generation-time snapshots. Section rows, summaries, resolved time
metadata, evidence metadata, source metadata, and the download artifact are persisted before a terminal
status is exposed. `GET` and download endpoints never invoke Analytics. If a source changes from 100 to
200 after generation, the historical detail and artifact still contain 100.

The report stores both the requested report window and each section's actual resolved query window.
`Generated At` is the report completion time. `Source As Of` is the latest source-snapshot reference captured
by the completed sections. Because the aggregate views do not expose a newest-fact timestamp, this is the
source-read capture time, not a claim that every underlying fact was observed at the window's upper bound.
These concepts are intentionally shown separately.

## Evidence

Each completed section stores safe evidence metadata: source system, catalog metric, report-section entity,
observation/as-of time, child analysis run reference, and a safe row-count summary. It also stores the
structured section rows themselves. Therefore an expired child analysis run does not make the report
unreadable. SQL, connection strings, credentials, provider responses, and exception text are never copied.

The report trace uses the unified Execution Trace model. Its durable trace records are stored inside the same
report snapshot and exposed through an `ExecutionEventArchive`; the in-memory trace projection can be
rehydrated after restart. Following the existing orchestration convention, `PARTIAL` terminates with a
`RUN_COMPLETED` event whose safe summary explicitly says the report is partial; `FAILED` terminates with
`RUN_FAILED`.

## Permissions

Creation, list, detail, and download retain the existing report role boundary: `OPERATOR` and `ADMIN` only.
An `OPERATOR` sees reports created under the `OPERATOR` demo scope; `ADMIN` may see every demo report.
`VIEWER`, `APPROVER`, and `CUSTOMER_AGENT` are denied. The same scope is enforced for report traces.

`X-Demo-Role` is demo authorization only. There is no production user identity, tenant ownership, or
production-grade report isolation in this repository. `requestedBy` therefore records a safe demo-role actor,
not a claimed real user. Existing in-memory audit records create/list/read/download actions.

Persistence and read APIs are configured independently from Analytics. If Analytics is disabled or unavailable,
history, detail, download, and trace replay remain available; only creation is rejected with `503`.

## API and download format

- `POST /api/operations-reports` creates or replays one report; `Idempotency-Key` is required.
- `GET /api/operations-reports` returns a descending, paged history and supports report type, status, and
  half-open created-time filters.
- `GET /api/operations-reports/{reportId}` returns the persisted detail snapshot.
- `GET /api/operations-reports/{reportId}/download` returns the persisted Markdown artifact.

The client cannot submit SQL, HTML, an artifact filename, or a server path. Artifact identifiers and metadata
are server-generated. The filename contains only the fixed product prefix and report date. Download lookup is
by `reportId`, applies the same authorization as detail, and never accepts a filesystem path. The artifact
records content type, byte size, SHA-256 checksum, creation time, and renderer version.

## Idempotency and concurrency

The idempotency fingerprint includes role, report type, timezone, and the exact half-open report window.
The same key and payload returns the same `reportId` without rerunning sections, including after restart. The
same key with a different payload returns a conflict. Store admission limits protect double clicks, multiple
tabs, slow responses, and retry storms; the UI also prevents concurrent clicks and ignores stale callbacks.

## Recovery

Section execution is not resumable because the underlying natural-language analysis run is not durable.
At startup, every persisted `REQUESTED` or `GENERATING` report is atomically recovered:

- if at least one section is complete, it becomes `PARTIAL`, preserving those sections and producing a
  download artifact;
- otherwise it becomes `FAILED`;
- every unfinished section records `GENERATION_INTERRUPTED`;
- a matching durable terminal trace event is committed with the report state.

No report remains permanently `GENERATING` after restart.

## Schema and retention

Every report includes `schemaVersion` and `generationVersion`; every artifact includes `rendererVersion`.
The current reader exposes schema version 1. Records with another version are preserved verbatim, excluded from
current APIs, and count toward retention rather than being silently misread or preventing startup. Retention is
count-based. A user-facing delete API is intentionally out of scope.

## Known limitations

- The durable adapter is intended for a bounded single-instance demo/runtime. It is not a shared multi-node
  transaction store.
- Report generation still requires the Analytics capability and its model/database dependencies.
- AuditTrail is currently in memory, although the report and report trace are durable.
- Markdown is the only download format in this slice; PDF, scheduling, email, designers, custom SQL,
  predictive maintenance, and production IAM/tenant isolation are out of scope.
