import { afterEach, describe, expect, it, vi } from 'vitest'
import { getAnomalyEvidence, getAnomalyOverview } from './operationsAnomalyApi'

afterEach(() => vi.unstubAllGlobals())

describe('operationsAnomalyApi', () => {
  it('rejects a malformed successful evidence response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })))

    await expect(getAnomalyEvidence('ADMIN', 'B1')).rejects.toThrow('异常证据响应格式无效')
  })

  it('rejects unknown or missing evidence domain availability values', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      buildingId: 'B1',
      window: { from: '2026-09-01T00:00:00Z', to: '2026-09-02T00:00:00Z', timezone: 'Asia/Shanghai' },
      asOf: null,
      alerts: [],
      devices: [],
      energy: [],
      domainStatus: { alerts: 'OK', devices: 'UNKNOWN' },
    }), { status: 200 })))

    await expect(getAnomalyEvidence('VIEWER', 'B1')).rejects.toThrow('异常证据响应格式无效')
  })

  it('rejects overview responses without all governed domain statuses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      window: { from: '2026-09-01T00:00:00Z', to: '2026-09-02T00:00:00Z', timezone: 'Asia/Shanghai' },
      asOf: null,
      summary: { alertCount: 0, highRiskAlertCount: 0, offlineDeviceCount: 0, affectedBuildingCount: 0 },
      breakdowns: {},
      buildings: [],
      domainStatus: { alerts: 'OK', devices: 'OK' },
    }), { status: 200 })))

    await expect(getAnomalyOverview('VIEWER')).rejects.toThrow('异常雷达响应格式无效')
  })
})
