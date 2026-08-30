import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AnalyticsChart, { withDarkTheme } from './AnalyticsChart.vue'
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

  it('applies the immersive dark theme to axes, tooltip and series', () => {
    const wrapper = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'LINE', xField: 'building', yFields: ['energy_kwh'], seriesField: '-' }),
        columns: ['building', 'energy_kwh'],
        rows: [['A1', 100], ['A2', 120]],
      },
    })

    const option = setOption.mock.calls.at(-1)?.[0]
    expect(option.backgroundColor).toBe('transparent')
    expect(option.textStyle.color).toBe('#c8d3e0')
    expect(option.xAxis.axisLabel.color).toBe('#98a4b6')
    expect(option.series[0].itemStyle.color).toBe('#70e8ff')
    wrapper.unmount()
  })

  it('decorates array axes and series styles without mutating the source option', () => {
    const source = {
      xAxis: [{ type: 'category', axisLabel: { rotate: 30 } }],
      yAxis: [{ type: 'value', splitLine: { show: false } }],
      series: [{ type: 'line', data: [100, 120], itemStyle: { borderWidth: 2 }, lineStyle: { width: 3 } }],
    }
    const original = structuredClone(source)

    const option = withDarkTheme(source) as unknown as {
      xAxis: Array<{ axisLabel: Record<string, unknown> }>
      yAxis: Array<{ splitLine: Record<string, unknown> }>
      series: Array<{ data: number[]; itemStyle: Record<string, unknown>; lineStyle: Record<string, unknown> }>
    }

    expect(option.xAxis).not.toBe(source.xAxis)
    expect(option.xAxis[0].axisLabel).toMatchObject({ rotate: 30, color: '#98a4b6' })
    expect(option.yAxis[0].splitLine).toMatchObject({ show: false, lineStyle: { color: 'rgba(176, 190, 208, 0.12)' } })
    expect(option.series[0].data).toEqual([100, 120])
    expect(option.series[0].itemStyle).toMatchObject({ borderWidth: 2, color: '#70e8ff' })
    expect(option.series[0].lineStyle).toMatchObject({ width: 3, color: '#70e8ff' })
    expect(source).toEqual(original)
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

  it('keeps heatmap, calendar and gauge-specific dark settings after decoration', () => {
    const heatmap = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'HEATMAP' }),
        columns: ['stat_date', 'hour_of_day', 'energy_kwh'],
        rows: [['2026-08-27', 9, 100]],
      },
    })
    const heatmapOption = setOption.mock.calls.at(-1)?.[0]
    expect(heatmapOption.visualMap).toMatchObject({
      textStyle: { color: '#98a4b6' },
      inRange: { color: ['#172334', '#275c73', '#70e8ff', '#ffd27a'] },
    })
    heatmap.unmount()

    const calendar = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'CALENDAR_HEATMAP', xField: 'stat_date', seriesField: '-' }),
        columns: ['stat_date', 'energy_kwh'],
        rows: [['2026-08-27', 100]],
      },
    })
    const calendarOption = setOption.mock.calls.at(-1)?.[0]
    expect(calendarOption.calendar).toMatchObject({
      itemStyle: { color: 'rgba(8, 12, 20, 0.72)' },
      dayLabel: { color: '#98a4b6' },
      yearLabel: { color: '#fff0d2' },
    })
    calendar.unmount()

    const gauge = mount(AnalyticsChart, {
      props: {
        chart: chart({ type: 'GAUGE', yFields: ['energy_kwh'], targetValue: 200 }),
        columns: ['energy_kwh'],
        rows: [[100]],
      },
    })
    const gaugeOption = setOption.mock.calls.at(-1)?.[0]
    expect(gaugeOption.series[0]).toMatchObject({
      axisLabel: { color: '#98a4b6' },
      detail: { color: '#70e8ff' },
      title: { color: '#c8d3e0' },
    })
    gauge.unmount()
  })
})
