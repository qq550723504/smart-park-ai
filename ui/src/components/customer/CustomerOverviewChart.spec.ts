import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CustomerOverviewChart from './CustomerOverviewChart.vue'

const chart = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
}))

vi.mock('echarts', () => ({
  init: vi.fn(() => chart),
}))

describe('CustomerOverviewChart', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders current and previous observed periods as two truthful line series', () => {
    mount(CustomerOverviewChart, {
      props: {
        kind: 'line',
        data: [{ name: '08:00', value: 120 }, { name: '09:00', value: 150 }],
        comparisonData: [{ name: '08:00', value: 100 }, { name: '09:00', value: 130 }],
        unit: 'kWh',
        label: '能耗对比',
      },
    })

    const option = chart.setOption.mock.calls.at(-1)?.[0]
    expect(option.legend).toBeTruthy()
    expect(option.series).toMatchObject([
      { name: '近 24 小时', data: [120, 150] },
      { name: '前 24 小时', data: [100, 130] },
    ])
  })

  it('shows the real donut total and derives legend percentages from the same values', () => {
    mount(CustomerOverviewChart, {
      props: {
        kind: 'donut',
        data: [{ name: '创新中心', value: 30 }, { name: '研发大厦', value: 70 }],
        unit: 'kWh',
        label: '楼宇能耗分布',
      },
    })

    const option = chart.setOption.mock.calls.at(-1)?.[0]
    expect(option.title).toMatchObject({ text: '100', subtext: 'kWh' })
    expect(option.legend.formatter('创新中心')).toBe('创新中心  30%')
    expect(option.legend.formatter('研发大厦')).toBe('研发大厦  70%')
  })
})
