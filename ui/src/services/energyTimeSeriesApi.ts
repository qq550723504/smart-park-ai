import type { DemoRole } from '../types/workflow'
import type { EnergyTimeSeriesFilters, EnergyTimeSeriesResponse, EnergyTimeSeriesStatus } from '../types/energyTimeSeries'

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isStatus(value: unknown): value is EnergyTimeSeriesStatus {
  return value === 'AVAILABLE' || value === 'PARTIAL' || value === 'UNAVAILABLE'
}

function assertResponse(value: unknown): asserts value is EnergyTimeSeriesResponse {
  if (!isRecord(value)
    || typeof value.metric !== 'string'
    || typeof value.unit !== 'string'
    || typeof value.timezone !== 'string'
    || !isStatus(value.status)
    || (value.asOf !== null && typeof value.asOf !== 'string')
    || !isRecord(value.window)
    || typeof value.window.from !== 'string'
    || typeof value.window.to !== 'string'
    || !['HOUR', 'DAY'].includes(String(value.window.granularity))
    || !isRecord(value.source)
    || typeof value.source.system !== 'string'
    || typeof value.source.metricDefinition !== 'string'
    || !isStatus(value.source.status)
    || !Array.isArray(value.series)
    || !value.series.every((series) => isRecord(series)
      && typeof series.buildingId === 'string'
      && Array.isArray(series.missingTimestamps)
      && series.missingTimestamps.every((timestamp) => typeof timestamp === 'string')
      && Array.isArray(series.points)
      && series.points.every((point) => isRecord(point)
        && typeof point.timestamp === 'string'
        && typeof point.value === 'number'
        && Number.isFinite(point.value)))
    || !Array.isArray(value.evidence)
    || !value.evidence.every(isRecord)) {
    throw new Error('能耗时序响应格式无效')
  }
}

export async function getEnergyTimeSeries(role: DemoRole, filters: EnergyTimeSeriesFilters): Promise<EnergyTimeSeriesResponse> {
  const params = new URLSearchParams()
  params.set('buildingIds', filters.buildingIds.join(','))
  if (filters.metric) params.set('metric', filters.metric)
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (filters.granularity) params.set('granularity', filters.granularity)
  const response = await fetch(`/api/operations/energy-time-series?${params.toString()}`, {
    headers: { 'X-Demo-Role': role },
  })
  if (!response.ok) throw new Error(`能耗时序请求失败（${response.status}）`)
  const value = await response.json() as unknown
  assertResponse(value)
  return value
}
