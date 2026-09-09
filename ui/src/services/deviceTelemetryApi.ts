import type { DemoRole } from '../types/workflow'
import type { DeviceHealthResponse, DeviceTelemetryResponse, TelemetryStatus } from '../types/deviceTelemetry'

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isStatus(value: unknown): value is TelemetryStatus {
  return value === 'AVAILABLE' || value === 'PARTIAL' || value === 'UNAVAILABLE'
}

function assertTelemetry(value: unknown): asserts value is DeviceTelemetryResponse {
  if (!isRecord(value) || typeof value.telemetryType !== 'string' || typeof value.unit !== 'string'
    || typeof value.timezone !== 'string' || !isStatus(value.status) || !isRecord(value.window)
    || typeof value.window.from !== 'string' || typeof value.window.to !== 'string'
    || value.window.granularity !== 'HOUR' || !isRecord(value.source)
    || typeof value.source.system !== 'string' || !['DEMO', 'PRODUCTION', 'NONE'].includes(String(value.source.datasetKind))
    || !Array.isArray(value.series) || !value.series.every((series) => isRecord(series)
      && typeof series.deviceId === 'string' && typeof series.buildingId === 'string'
      && typeof series.deviceType === 'string' && ['FRESH', 'STALE', 'UNKNOWN'].includes(String(series.freshness))
      && Array.isArray(series.missingTimestamps) && series.missingTimestamps.every((item) => typeof item === 'string')
      && Array.isArray(series.points) && series.points.every((point) => isRecord(point)
        && typeof point.timestamp === 'string' && typeof point.value === 'number' && Number.isFinite(point.value)
        && typeof point.quality === 'string'))) {
    throw new Error('设备遥测响应格式无效')
  }
}

function assertHealth(value: unknown): asserts value is DeviceHealthResponse {
  if (!isRecord(value) || typeof value.deviceId !== 'string'
    || !['HEALTHY', 'ATTENTION', 'DEGRADED', 'CRITICAL', 'UNKNOWN'].includes(String(value.healthStatus))
    || !isStatus(value.availability) || !Array.isArray(value.reasons)
    || !value.reasons.every((reason) => typeof reason === 'string')
    || !Array.isArray(value.evidence) || !value.evidence.every(isRecord)
    || !Array.isArray(value.sources) || !value.sources.every(isRecord)
    || (value.asOf !== null && typeof value.asOf !== 'string')) {
    throw new Error('设备健康响应格式无效')
  }
}

export async function getDeviceTelemetry(role: DemoRole, deviceIds: string[]): Promise<DeviceTelemetryResponse> {
  const params = new URLSearchParams({ telemetryType: 'TEMPERATURE', deviceIds: deviceIds.join(','), granularity: 'HOUR' })
  const response = await fetch(`/api/operations/device-telemetry?${params.toString()}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw new Error(`设备遥测请求失败（${response.status}）`)
  const value = await response.json() as unknown
  assertTelemetry(value)
  return value
}

export async function getDeviceHealth(role: DemoRole, deviceId: string): Promise<DeviceHealthResponse> {
  const response = await fetch(`/api/operations/device-health/${encodeURIComponent(deviceId)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw new Error(`设备健康请求失败（${response.status}）`)
  const value = await response.json() as unknown
  assertHealth(value)
  return value
}
