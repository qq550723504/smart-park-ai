# Device telemetry and explainable health

## Capability matrix

| Capability | Status | Current source |
| --- | --- | --- |
| Device connectivity | `DIRECT_REUSE` | `analytics.v_device_snapshot` |
| Active alerts | `DIRECT_REUSE` | `analytics.v_alert_fact` |
| Temperature telemetry | `ADAPTED` | Deterministic PostgreSQL demo facts exposed by `analytics.v_device_telemetry_hourly` |
| Vibration telemetry | `NOT_READY` | No datasource is connected |
| Temperature thresholds | `ADAPTED` | Explicit demo policy `DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1` |
| Device health state | `ADAPTED` | Connectivity, device-scoped active alerts, and registered temperature evidence |
| Numeric health score | `NOT_READY` | No approved 0–100 business definition exists |
| Predictive maintenance model | `NOT_READY` | No validated failure prediction, RUL, or recommendation model exists |

`Demo telemetry is for product demonstration and contract verification. It is not production device telemetry.`

## Data source and grain

The repository did not contain device telemetry history before Issue #60. `DevicePort` exposes a current mock device only, while `analytics.v_device_snapshot` exposes a current analytics snapshot. Alert evidence strings such as `sensor:temp-01` are not raw sensor readings and are never converted into a curve.

The first adapter therefore uses a deliberately named demo table:

| Field | Value |
| --- | --- |
| Raw relation | `analytics.device_telemetry_demo_hourly_raw` (admin only) |
| Read-only relation | `analytics.v_device_telemetry_hourly` |
| Registered telemetry | `TEMPERATURE` only |
| Unit | `°C` from the telemetry catalog |
| Grain | One device and telemetry type per hour |
| Timezone | `Asia/Shanghai` contract; timestamps are returned as instants |
| Quality | Source value (`GOOD` in the deterministic fixture) |
| Source label | `OPERATIONS_ANALYTICS_DEMO` |
| Availability | `AVAILABLE`, `PARTIAL`, or `UNAVAILABLE` |

The migration and optional demo refresher generate repeatable, relative-time fixtures. Specific buckets are omitted deliberately so the `PARTIAL` path is exercised. Missing buckets are returned in `missingTimestamps`; they are never zero-filled, forward-filled, interpolated, or smoothed.

Production onboarding must replace the demo relation with an ownership-verified adapter while preserving the public contract and controlled catalog. It must also reconcile the existing mock-domain IDs (for example `DEV-HVAC-001`) with analytics IDs (for example `AC-B1-07`) through an authoritative device identity mapping. Issue #60 does not pretend that this pre-existing master-data gap is solved.

## API contract

Read roles are `VIEWER`, `OPERATOR`, `APPROVER`, and `ADMIN`. `CUSTOMER_AGENT` is denied. Both endpoints are read-only and introduce no device control command.

```text
GET /api/operations/device-telemetry
  ?deviceIds=AC-B1-07,HUM-B2-11
  &telemetryType=TEMPERATURE
  &from=...
  &to=...
  &granularity=HOUR

GET /api/operations/device-health/{deviceId}
```

Telemetry returns `telemetryType`, `unit`, `timezone`, `window`, `status`, device series, missing timestamps, freshness, optional registered threshold, `asOf`, safe source metadata, and evidence counts. `VIBRATION` is a known `NOT_READY` capability and returns `UNAVAILABLE` with no series. Unknown telemetry strings are rejected.

Bounds are enforced before datasource access:

- At most 10 devices.
- At most 7 days.
- Hour-aligned windows only in this version.
- At most 500 requested device-buckets and 500 returned rows.

## Safe query path

Both telemetry and health fact readers use:

```text
TelemetryCatalog / fixed query shape
  -> SqlAstGuard
  -> named parameter binding
  -> QueryCostGuard (EXPLAIN)
  -> ReadOnlyQueryExecutor
  -> PostgreSQL read-only view privileges
```

Times are bound as UTC `OffsetDateTime` for both EXPLAIN and execution. Public errors expose only stable availability/error contracts; relation names, JDBC URLs, hosts, credentials, tokens, stack traces, and private device URLs are not returned.

## Health model and evidence rules

Health is a deterministic state, never an AI-authored sensor fact or numeric score:

- `CRITICAL`: fresh `OFFLINE`, an active `HIGH` alert, or a fresh latest telemetry point above the registered critical threshold.
- `DEGRADED`: an active `MEDIUM` alert or three consecutive fresh points above the registered attention threshold.
- `ATTENTION`: an active `LOW` alert, snapshot `DEGRADED`, or a fresh latest point above the attention threshold.
- `HEALTHY`: only when the device snapshot is fresh, telemetry and its registered threshold are present and fresh, and no registered signal triggers a rule.
- `UNKNOWN`: stale/missing telemetry, stale connectivity, an unsupported device type, or otherwise insufficient evidence. Missing evidence never defaults to `HEALTHY`.

Each response includes reasons, typed evidence references, safe source labels, `asOf`, and availability. Alerts are accepted only when both `deviceId` and `buildingId` match the assessed device. A scope mismatch fails closed instead of mixing evidence across devices or buildings.

The only threshold profile is explicitly fictional and registered for the HVAC temperature demo:

```text
DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1
attentionAbove = 28.0 °C
criticalAbove = 35.0 °C
```

It is not an industry recommendation. A production threshold must come from approved business policy or source metadata and carry its own provenance. Without a threshold, raw telemetry may be returned but no threshold breach is inferred.

## Dashboard and orchestration

The operations cockpit renders temperature and vibration as separate capabilities. The temperature chart uses API timestamps, units, and source metadata; declared gaps are inserted as `null` to break the ECharts line. `UNAVAILABLE` never mounts a chart. Health displays state, reasons, sources, and evidence count, not a score.

`JOINT_ANOMALY_ASSESSMENT` performs only a minimal conditional read during context collection: an `alertId` must resolve through the analytics alert fact to a device, analytics must be enabled, a telemetry source must be `AVAILABLE` or `PARTIAL`, and device health must return usable non-`UNKNOWN` evidence. Otherwise the optional enrichment is omitted and the existing orchestration remains intact.

## Known limitations and predictive-maintenance boundary

- The shipped telemetry is deterministic demo data, not MQTT/SCADA/BMS/IoT ingestion.
- Only hourly temperature is registered; vibration/current/voltage/pressure/flow/runtime are not connected.
- No production device master-data mapping exists between the mock domain and analytics fixtures.
- No interpolation, anomaly-learning model, maintenance history model, or causal energy-device model is included.
- Energy deviation is not used to claim that a device caused increased consumption.

The data/health foundation can be exercised, but predictive-maintenance data readiness is only partial and the predictive-maintenance model remains `NOT_READY`.
