import { afterEach, describe, expect, it, vi } from 'vitest'
import { getDeviceHealth, getDeviceTelemetry } from './deviceTelemetryApi'

const telemetry = {
  telemetryType: 'TEMPERATURE', unit: '°C', timezone: 'Asia/Shanghai',
  window: { from: '2026-09-08T08:00:00Z', to: '2026-09-08T10:00:00Z', granularity: 'HOUR' },
  status: 'PARTIAL',
  series: [{ deviceId: 'AC-B1-07', buildingId: 'B1', deviceType: 'HVAC', freshness: 'FRESH',
    points: [{ timestamp: '2026-09-08T08:00:00Z', value: 30.5, quality: 'GOOD' }],
    missingTimestamps: ['2026-09-08T09:00:00Z'] }],
  threshold: { attentionAbove: 28, criticalAbove: 35, source: 'DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1' },
  asOf: '2026-09-08T08:00:00Z',
  source: { system: 'OPERATIONS_ANALYTICS_DEMO', telemetryType: 'TEMPERATURE', status: 'PARTIAL', datasetKind: 'DEMO' },
  evidence: [],
}
const health = { deviceId: 'AC-B1-07', buildingId: 'B1', deviceType: 'HVAC', healthStatus: 'DEGRADED',
  availability: 'PARTIAL', reasons: ['温度持续超过已登记阈值'], evidence: [],
  sources: [{ system: 'OPERATIONS_ANALYTICS_DEMO', status: 'PARTIAL' }], asOf: '2026-09-08T08:00:00Z' }

describe('deviceTelemetryApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends controlled parameters and role without accepting SQL', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => telemetry })
    vi.stubGlobal('fetch', fetch)

    const value = await getDeviceTelemetry('VIEWER', ['AC-B1-07'])

    expect(value.status).toBe('PARTIAL')
    const [url, init] = fetch.mock.calls[0]
    expect(url).toContain('telemetryType=TEMPERATURE')
    expect(url).toContain('deviceIds=AC-B1-07')
    expect(url).not.toContain('sql')
    expect(init.headers).toEqual({ 'X-Demo-Role': 'VIEWER' })
  })

  it('validates telemetry values and health states before rendering', async () => {
    const malformed = structuredClone(telemetry) as unknown as { series: Array<{ points: Array<{ value: unknown }> }> }
    malformed.series[0].points[0].value = '30.5'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => malformed }))
    await expect(getDeviceTelemetry('VIEWER', ['AC-B1-07'])).rejects.toThrow('设备遥测响应格式无效')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ...health, healthStatus: '97' }) }))
    await expect(getDeviceHealth('VIEWER', 'AC-B1-07')).rejects.toThrow('设备健康响应格式无效')
  })

  it('does not surface backend error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500,
      json: async () => ({ message: 'jdbc://secret-host password=secret' }) }))
    await expect(getDeviceHealth('VIEWER', 'AC-B1-07')).rejects.toThrow('设备健康请求失败（500）')
  })
})
