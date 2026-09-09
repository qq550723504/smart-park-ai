export type TelemetryStatus = 'AVAILABLE' | 'PARTIAL' | 'UNAVAILABLE'
export type TelemetryFreshness = 'FRESH' | 'STALE' | 'UNKNOWN'
export type HealthStatus = 'HEALTHY' | 'ATTENTION' | 'DEGRADED' | 'CRITICAL' | 'UNKNOWN'

export interface DeviceTelemetryResponse {
  telemetryType: string
  unit: string
  timezone: string
  window: { from: string; to: string; granularity: 'HOUR' }
  status: TelemetryStatus
  series: Array<{
    deviceId: string
    buildingId: string
    deviceType: string
    points: Array<{ timestamp: string; value: number; quality: string }>
    missingTimestamps: string[]
    freshness: TelemetryFreshness
  }>
  threshold: { attentionAbove: number; criticalAbove: number; source: string } | null
  asOf: string | null
  source: { system: string; telemetryType: string; status: TelemetryStatus; datasetKind: 'DEMO' | 'PRODUCTION' | 'NONE' }
  evidence: Array<{ deviceId: string; expectedPointCount: number; actualPointCount: number; firstObservedAt: string | null; lastObservedAt: string | null }>
}

export interface DeviceHealthResponse {
  deviceId: string
  buildingId: string | null
  deviceType: string | null
  healthStatus: HealthStatus
  availability: TelemetryStatus
  reasons: string[]
  evidence: Array<{ type: string; reference: string; occurredAt: string | null; summary: string }>
  sources: Array<{ system: string; status: TelemetryStatus }>
  asOf: string | null
}
