import { afterEach, describe, expect, it, vi } from 'vitest'
import { getEnergyTimeSeries } from './energyTimeSeriesApi'

const response = {
  metric: 'energy_kwh',
  unit: 'kWh',
  timezone: 'Asia/Shanghai',
  window: { from: '2026-09-07T00:00:00Z', to: '2026-09-07T02:00:00Z', granularity: 'HOUR' },
  status: 'AVAILABLE',
  series: [{ buildingId: 'B1', points: [{ timestamp: '2026-09-07T00:00:00Z', value: 12.5 }], missingTimestamps: [] }],
  asOf: '2026-09-07T00:00:00Z',
  source: { system: 'OPERATIONS_ANALYTICS', metricDefinition: 'energy_kwh', status: 'AVAILABLE' },
  evidence: [{ buildingId: 'B1', expectedPointCount: 1, actualPointCount: 1, firstObservedAt: '2026-09-07T00:00:00Z', lastObservedAt: '2026-09-07T00:00:00Z' }],
}

describe('energyTimeSeriesApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends only typed filters and validates the stable response', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => response })
    vi.stubGlobal('fetch', fetch)

    const value = await getEnergyTimeSeries('VIEWER', {
      buildingIds: ['B1', 'B2'], metric: 'energy_kwh', granularity: 'HOUR',
      from: '2026-09-07T00:00:00Z', to: '2026-09-07T02:00:00Z',
    })

    expect(value.unit).toBe('kWh')
    expect(fetch).toHaveBeenCalledOnce()
    const [url, init] = fetch.mock.calls[0]
    expect(url).toContain('/api/operations/energy-time-series?')
    expect(url).toContain('buildingIds=B1%2CB2')
    expect(url).not.toContain('sql')
    expect(init.headers).toEqual({ 'X-Demo-Role': 'VIEWER' })
  })

  it('rejects malformed point values instead of drawing them', async () => {
    const malformed = structuredClone(response) as unknown as { series: Array<{ points: Array<{ value: unknown }> }> }
    malformed.series[0].points[0].value = '12.5'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => malformed }))

    await expect(getEnergyTimeSeries('VIEWER', { buildingIds: ['B1'] }))
      .rejects.toThrow('能耗时序响应格式无效')
  })

  it('does not surface backend error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 500, json: async () => ({ message: 'jdbc://secret-host password=secret' }),
    }))

    await expect(getEnergyTimeSeries('VIEWER', { buildingIds: ['B1'] }))
      .rejects.toThrow('能耗时序请求失败（500）')
  })
})
