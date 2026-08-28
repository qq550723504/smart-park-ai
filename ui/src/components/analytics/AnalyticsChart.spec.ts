import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AnalyticsChart from './AnalyticsChart.vue'
import type { DisplayPayload } from '../../types/execution'

const { setOption, dispose, init } = vi.hoisted(() => {
  const setOption = vi.fn()
  const dispose = vi.fn()
  return { setOption, dispose, init: vi.fn(() => ({ setOption, dispose })) }
})

vi.mock('echarts', () => ({ init }))

function chart(overrides: Partial<Extract<DisplayPayload, { payloadType: 'CHART' }>>): DisplayPayload {
  return {
    payloadType: 'CHART',
    type: 'HEATMAP',
    title: '时段热力',
    xField: 'stat_date',
    yFields: ['energy_kwh'],
    seriesField: 'hour_of_day',
    unit: 'kWh',
    orientation: 'VERTICAL',
    stacked: false,
    targetValue: null,
    coordinateXField: '',
    coordinateYField: '',
    ...overrides,
  }
}

describe('AnalyticsChart', () => {
  it('renders heatmap options from real two-dimensional result rows', () => {
    const wrapper = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'HEATMAP' }),
        columns: ['stat_date', 'hour_of_day', 'energy_kwh'],
        rows: [['2026-08-27', 9, 100], ['2026-08-28', 10, 120]],
      },
    })

    expect(init).toHaveBeenCalled()
    const option = setOption.mock.calls.at(-1)?.[0]
    expect(option.series[0].type).toBe('heatmap')
    expect(option.series[0].data).toEqual([[0, 0, 100], [1, 1, 120]])
    wrapper.unmount()
  })

  it('renders KPI values as accessible text instead of inventing a chart point', () => {
    const wrapper = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'KPI', title: '总能耗', xField: 'energy_kwh', yFields: ['energy_kwh'], seriesField: '-' }),
        columns: ['energy_kwh'],
        rows: [[4618]],
      },
    })

    expect(wrapper.find('[data-testid="analytics-kpi"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('4618')
    wrapper.unmount()
  })

  it('supports calendar, scatter, gauge and map chart specs', () => {
    for (const type of ['CALENDAR_HEATMAP', 'SCATTER', 'GAUGE', 'MAP'] as const) {
      const wrapper = mount(AnalyticsChart, {
        props: {
          chart: chart({
            type,
            xField: type === 'SCATTER' ? 'area_sqm' : 'building_name',
            yFields: ['energy_kwh'],
            seriesField: '-',
            coordinateXField: 'map_x',
            coordinateYField: 'map_y',
          }),
          columns: ['building_name', 'area_sqm', 'stat_date', 'energy_kwh', 'map_x', 'map_y'],
          rows: [['创新中心', 32000, '2026-08-27', 100, 12.5, 35]],
        },
      })
      expect(wrapper.find('.analytics-chart').exists() || wrapper.find('[data-testid="analytics-kpi"]').exists()).toBe(true)
      wrapper.unmount()
    }
  })

  it('uses a valid single-date calendar range', () => {
    const wrapper = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'CALENDAR_HEATMAP', xField: 'stat_date', yFields: ['energy_kwh'], seriesField: '-' }),
        columns: ['stat_date', 'energy_kwh'],
        rows: [['2026-08-27', 100]],
      },
    })

    const option = setOption.mock.calls.at(-1)?.[0]
    expect(option.calendar.range).toBe('2026-08-27')
    wrapper.unmount()
  })
})
