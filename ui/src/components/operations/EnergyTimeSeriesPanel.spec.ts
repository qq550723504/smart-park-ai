import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EnergyTimeSeriesPanel from './EnergyTimeSeriesPanel.vue'
import type { EnergyTimeSeriesResponse } from '../../types/energyTimeSeries'

const mocks = vi.hoisted(() => ({
  getEnergyTimeSeries: vi.fn(), setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn(), init: vi.fn(),
}))

vi.mock('../../services/energyTimeSeriesApi', () => ({ getEnergyTimeSeries: mocks.getEnergyTimeSeries }))
vi.mock('echarts', () => ({ init: mocks.init }))

const available: EnergyTimeSeriesResponse = {
  metric: 'energy_kwh', unit: 'kWh', timezone: 'Asia/Shanghai',
  window: { from: '2026-09-07T00:00:00Z', to: '2026-09-07T03:00:00Z', granularity: 'HOUR' },
  status: 'AVAILABLE',
  series: [{
    buildingId: 'B1',
    points: [
      { timestamp: '2026-09-07T00:00:00Z', value: 12.5 },
      { timestamp: '2026-09-07T01:00:00Z', value: 15 },
    ],
    missingTimestamps: [],
  }],
  asOf: '2026-09-07T01:00:00Z',
  source: { system: 'OPERATIONS_ANALYTICS', metricDefinition: 'energy_kwh', status: 'AVAILABLE' },
  evidence: [{ buildingId: 'B1', expectedPointCount: 2, actualPointCount: 2, firstObservedAt: '2026-09-07T00:00:00Z', lastObservedAt: '2026-09-07T01:00:00Z' }],
}

class MockResizeObserver {
  observe = vi.fn()
  disconnect = vi.fn()
  constructor(private readonly callback: ResizeObserverCallback) {}
}

describe('EnergyTimeSeriesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.init.mockImplementation(() => ({ setOption: mocks.setOption, dispose: mocks.dispose, resize: mocks.resize }))
    vi.stubGlobal('ResizeObserver', MockResizeObserver)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('draws only API timestamps and values and uses the API unit in axes and tooltip', async () => {
    mocks.getEnergyTimeSeries.mockResolvedValue(available)
    const wrapper = mount(EnergyTimeSeriesPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(mocks.getEnergyTimeSeries).toHaveBeenCalledWith('VIEWER', {
      buildingIds: ['B1', 'B2', 'B3'], metric: 'energy_kwh', granularity: 'HOUR',
    })
    const option = mocks.setOption.mock.calls.at(-1)?.[0]
    expect(option.yAxis.name).toBe('kWh')
    expect(option.series[0]).toMatchObject({ name: 'B1', connectNulls: false })
    expect(option.series[0].data).toEqual([
      ['2026-09-07T00:00:00Z', 12.5], ['2026-09-07T01:00:00Z', 15],
    ])
    expect(option.tooltip.valueFormatter(12.5)).toBe('12.5 kWh')
    expect(option.tooltip.valueFormatter(['2026-09-07T00:00:00Z', 12.5])).toBe('12.5 kWh')
    expect(option.tooltip.valueFormatter(['2026-09-07T02:00:00Z', null])).toBe('缺失')
    expect(wrapper.text()).toContain('AVAILABLE')
    wrapper.unmount()
  })

  it('shows PARTIAL and inserts null only to break a declared missing bucket', async () => {
    mocks.getEnergyTimeSeries.mockResolvedValue({
      ...available, status: 'PARTIAL', source: { ...available.source, status: 'PARTIAL' },
      series: [{ ...available.series[0], missingTimestamps: ['2026-09-07T02:00:00Z'] }],
    })
    const wrapper = mount(EnergyTimeSeriesPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(wrapper.get('[data-energy-partial]').text()).toContain('未补零')
    const data = mocks.setOption.mock.calls.at(-1)?.[0].series[0].data
    expect(data).toContainEqual(['2026-09-07T02:00:00Z', null])
    expect(data).not.toContainEqual(['2026-09-07T02:00:00Z', 0])
    wrapper.unmount()
  })

  it('does not initialize a chart for UNAVAILABLE or empty series', async () => {
    mocks.getEnergyTimeSeries.mockResolvedValue({
      ...available, status: 'UNAVAILABLE', series: [], asOf: null,
      source: { ...available.source, status: 'UNAVAILABLE' }, evidence: [],
    })
    const wrapper = mount(EnergyTimeSeriesPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(wrapper.get('[data-energy-unavailable]').text()).toContain('UNAVAILABLE')
    expect(wrapper.find('[data-energy-chart]').exists()).toBe(false)
    expect(mocks.init).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('uses a safe frontend error without exposing backend details', async () => {
    mocks.getEnergyTimeSeries.mockRejectedValue(new Error('jdbc://secret-host password=secret'))
    const wrapper = mount(EnergyTimeSeriesPanel, { props: { role: 'VIEWER' } })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('能耗时序暂不可用，请稍后重试。')
    expect(wrapper.text()).not.toContain('secret-host')
    expect(wrapper.text()).not.toContain('password')
    expect(wrapper.text()).not.toContain('jdbc')
    expect(mocks.init).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
