import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DeviceTelemetryHealthPanel from './DeviceTelemetryHealthPanel.vue'
import type { DeviceHealthResponse, DeviceTelemetryResponse } from '../../types/deviceTelemetry'

const mocks = vi.hoisted(() => ({ getDeviceTelemetry: vi.fn(), getDeviceHealth: vi.fn(),
  setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn(), init: vi.fn() }))
vi.mock('../../services/deviceTelemetryApi', () => ({
  getDeviceTelemetry: mocks.getDeviceTelemetry, getDeviceHealth: mocks.getDeviceHealth,
}))
vi.mock('echarts', () => ({ init: mocks.init }))

const telemetry: DeviceTelemetryResponse = {
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
const health: DeviceHealthResponse = { deviceId: 'AC-B1-07', buildingId: 'B1', deviceType: 'HVAC',
  healthStatus: 'DEGRADED', availability: 'PARTIAL', reasons: ['最近 3 个温度点持续超过已登记阈值'],
  evidence: [{ type: 'TELEMETRY_THRESHOLD', reference: 'telemetry:TEMPERATURE:x', occurredAt: telemetry.asOf, summary: 'DEMO_POLICY' }],
  sources: [{ system: 'DEVICE_SNAPSHOT', status: 'AVAILABLE' }, { system: 'OPERATIONS_ANALYTICS_DEMO', status: 'PARTIAL' }],
  asOf: telemetry.asOf!, }

class MockResizeObserver { observe = vi.fn(); disconnect = vi.fn(); constructor(_callback: ResizeObserverCallback) {} }

describe('DeviceTelemetryHealthPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.init.mockImplementation(() => ({ setOption: mocks.setOption, dispose: mocks.dispose, resize: mocks.resize }))
    vi.stubGlobal('ResizeObserver', MockResizeObserver)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('renders API values, a declared null gap, source tooltip and explainable health without a score', async () => {
    mocks.getDeviceTelemetry.mockResolvedValue(telemetry)
    mocks.getDeviceHealth.mockResolvedValue(health)
    const wrapper = mount(DeviceTelemetryHealthPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(mocks.getDeviceTelemetry).toHaveBeenCalledWith('VIEWER', ['AC-B1-07'])
    expect(wrapper.get('[data-telemetry-partial]').text()).toContain('不补零、不插值')
    expect(wrapper.get('[data-health-status="DEGRADED"]').text()).toBe('DEGRADED')
    expect(wrapper.text()).toContain('Demo 温度遥测')
    expect(wrapper.text()).toContain('振动遥测 · NOT_READY')
    expect(wrapper.text()).not.toMatch(/健康(度|分).*\d+/)
    const option = mocks.setOption.mock.calls.at(-1)?.[0]
    expect(option.series[0].connectNulls).toBe(false)
    expect(option.series[0].data).toContainEqual(['2026-09-08T09:00:00Z', null])
    expect(option.series[0].data).not.toContainEqual(['2026-09-08T09:00:00Z', 0])
    const tooltip = option.tooltip.formatter([{ axisValue: '09:00', data: ['x', 30.5] }])
    expect(tooltip).toContain('30.5 °C')
    expect(tooltip).toContain('OPERATIONS_ANALYTICS_DEMO')
    wrapper.unmount()
  })

  it('does not draw unavailable telemetry and keeps UNKNOWN first class', async () => {
    mocks.getDeviceTelemetry.mockResolvedValue({ ...telemetry, status: 'UNAVAILABLE', series: [], asOf: null,
      threshold: null, source: { ...telemetry.source, status: 'UNAVAILABLE' } })
    mocks.getDeviceHealth.mockResolvedValue({ ...health, healthStatus: 'UNKNOWN', availability: 'UNAVAILABLE',
      reasons: ['证据不足，不能推断设备健康'], evidence: [] })
    const wrapper = mount(DeviceTelemetryHealthPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(wrapper.get('[data-telemetry-unavailable]').text()).toContain('不生成趋势')
    expect(wrapper.get('[data-health-status="UNKNOWN"]').text()).toBe('UNKNOWN')
    expect(wrapper.find('[data-telemetry-chart]').exists()).toBe(false)
    expect(mocks.init).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('redacts backend failure details from the panel', async () => {
    mocks.getDeviceTelemetry.mockRejectedValue(new Error('jdbc://secret-host password=secret'))
    mocks.getDeviceHealth.mockRejectedValue(new Error('stack trace'))
    const wrapper = mount(DeviceTelemetryHealthPanel, { props: { role: 'VIEWER' } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('部分设备证据暂不可用')
    expect(wrapper.text()).not.toContain('secret-host')
    expect(wrapper.text()).not.toContain('password')
    expect(wrapper.text()).not.toContain('jdbc')
    expect(wrapper.text()).not.toContain('stack trace')
    wrapper.unmount()
  })
})
