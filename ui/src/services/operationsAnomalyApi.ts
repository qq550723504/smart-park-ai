import type { DemoRole } from '../types/workflow'
import type { AnomalyEvidence, AnomalyFilters, AnomalyOverview } from '../types/operationsAnomaly'

async function readError(response: Response): Promise<Error> {
  const detail = await response.json().catch(() => null) as { message?: string } | null
  return new Error(detail?.message ?? `请求失败（${response.status}）`)
}

function queryString(filters: AnomalyFilters = {}): string {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  const encoded = params.toString()
  return encoded ? `?${encoded}` : ''
}

function assertOverview(value: unknown): asserts value is AnomalyOverview {
  const candidate = isRecord(value) ? value : null
  const window = candidate && isRecord(candidate.window) ? candidate.window : null
  const summary = candidate && isRecord(candidate.summary) ? candidate.summary : null
  const breakdowns = candidate && isRecord(candidate.breakdowns) ? candidate.breakdowns : null
  if (!candidate || !window || typeof window.from !== 'string'
    || typeof window.to !== 'string' || typeof window.timezone !== 'string'
    || !summary || !['alertCount', 'highRiskAlertCount', 'offlineDeviceCount', 'affectedBuildingCount']
      .every((key) => typeof summary[key] === 'number')
    || !breakdowns || !isRecord(candidate.domainStatus) || !Array.isArray(candidate.buildings)
    || !candidate.buildings.every((item) => isRecord(item)
      && typeof item.buildingId === 'string'
      && typeof item.alertCount === 'number'
      && typeof item.highRiskAlertCount === 'number'
      && typeof item.offlineDeviceCount === 'number'
      && (item.energyDeviationPct === null || typeof item.energyDeviationPct === 'number'))
    || !Object.values(breakdowns).every((items) => Array.isArray(items)
      && items.every((item) => isRecord(item) && typeof item.key === 'string' && typeof item.count === 'number'))) {
    throw new Error('异常雷达响应格式无效')
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function assertEvidence(value: unknown): asserts value is AnomalyEvidence {
  if (!isRecord(value)
    || typeof value.buildingId !== 'string'
    || !isRecord(value.window)
    || typeof value.window.from !== 'string'
    || typeof value.window.to !== 'string'
    || typeof value.window.timezone !== 'string'
    || (value.asOf !== null && typeof value.asOf !== 'string')
    || !Array.isArray(value.alerts)
    || !Array.isArray(value.devices)
    || !Array.isArray(value.energy)
    || !value.alerts.every(isRecord)
    || !value.devices.every(isRecord)
    || !value.energy.every(isRecord)
    || !isRecord(value.domainStatus)) {
    throw new Error('异常证据响应格式无效')
  }
}

export async function getAnomalyOverview(role: DemoRole, filters: AnomalyFilters = {}): Promise<AnomalyOverview> {
  const response = await fetch(`/api/operations/anomaly-overview${queryString(filters)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw await readError(response)
  const value = await response.json() as unknown
  assertOverview(value)
  return value
}

export async function getAnomalyEvidence(role: DemoRole, buildingId: string, filters: AnomalyFilters = {}): Promise<AnomalyEvidence> {
  const response = await fetch(`/api/operations/anomaly-evidence/${encodeURIComponent(buildingId)}${queryString(filters)}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw await readError(response)
  const value = await response.json() as unknown
  assertEvidence(value)
  return value
}
