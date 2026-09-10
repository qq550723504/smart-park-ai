# Issue #72 capability map

## Reused product boundary

The fourth customer page is a view over the existing durable operations-report capability. It does not introduce another report engine or a second report store.

| Customer action | Existing capability | Lifecycle rule |
| --- | --- | --- |
| Open report center | `GET /api/operations-reports` | Reads persisted history only. It never starts generation. |
| Open one report | `GET /api/operations-reports/{reportId}` | Preview, summary, chart, conclusions and section tables all use this one response. |
| Generate explicitly | `POST /api/operations-reports` with `Idempotency-Key` | The only action allowed to create a report. A retry after an ambiguous response reuses the same key and request window. |
| Refresh / leave and return | History GET plus current-detail GET | Keeps the selected receipt and never converts navigation into a create call. |
| Download | `GET /api/operations-reports/{reportId}/download` | Downloads the artifact persisted for the selected report ID. |

## Truthful status contract

- `COMPLETED` is the only state rendered as `已生成`.
- `PARTIAL` stays `部分完成`; unavailable sections keep their reason and are not filled with zeroes or inferred values.
- `FAILED` stays `生成失败`; it is retained in history but is not presented as a successful preview or download.
- `REQUESTED` and `GENERATING` remain non-terminal progress states.
- A download control is enabled only when the selected detail says `downloadAvailable` and supplies its persisted artifact metadata.

## Deliberate exclusions

The approved mock includes weekly/monthly/special reports, PDF export, sharing and sending. The current server exposes one `OPERATIONS_DAILY` report type and one Markdown artifact. Those unsupported controls were omitted instead of being simulated. Scheduling, email, a second report engine, deployment, the implementation report and the assistant page remain outside #72.
